package org.fidonet.binkp;

import org.fidonet.io.Connector;
import org.fidonet.io.Frame;
import org.fidonet.io.Message;
import org.fidonet.io.ProtocolConnector;
import org.fidonet.logger.ILogger;
import org.fidonet.logger.LoggerFactory;
import org.fidonet.types.FTNAddr;
import org.fidonet.types.Link;

import java.io.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 
 * @author kreon
 * 
 */
public class BinkpConnector implements ProtocolConnector {

    private static String version = "1.0b";

    private static final DateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
    private static final ILogger logger = LoggerFactory.getLogger(BinkpConnector.class);
    private static final int STATE_WAITADDR = 1;
    private static final int STATE_WAITOK = 2;
    private static final int STATE_WAITPWD = 4;
    private static final int STATE_TRANSFER = 8;
    private static final int STATE_END = 16;
    private static final int STATE_ERR = 32;

    private boolean reob;
    private boolean leob;
    private boolean binkp1;
    private boolean recv;
    private boolean send;
    private boolean sendfile;
    private boolean recvfile;
    private List<Frame> frames;
    private int connectionState;
    private FTNAddr remoteFtnAddr;
    private List<Message> received;
    private Message currentMessage;
    private ByteArrayOutputStream currentOutputStream;
    private long currentMessageTimestamp;
    private int currentMessageBytesLeft;
    private boolean useCramMD5 = false;
    private String cramMD5Text;
    private String password;
    private boolean incoming = false;
    private Connector connector;
    private Link link;
    private int totalin;
    private int totalout;

    private StationConfig config;
    private List<Link> links;


    public void reset() {
        frames = new ArrayList<Frame>();
        received = new ArrayList<Message>();
        useCramMD5 = false;
        connector = null;
        link = null;
        totalin = 0;
        totalout = 0;
        connectionState = STATE_WAITADDR;
        recvfile = false;
        sendfile = false;
        reob = false;
        leob = false;
        binkp1 = false;
        recv = false;
        send = false;
    }

    public BinkpConnector(StationConfig config, List<Link> links) {
        this.config = config;
        this.links = links;

    }

    private void greet() {
        frames.add(new BinkpFrame(BinkpCommand.M_NUL, "SYS " + config.getName()));
        frames.add(new BinkpFrame(BinkpCommand.M_NUL, "ZYZ " + config.getSysopName()));
        frames.add(new BinkpFrame(BinkpCommand.M_NUL, "LOC " + config.getLocation()));
        frames.add(new BinkpFrame(BinkpCommand.M_NUL, "NDL " + config.getNDL()));
        frames.add(new BinkpFrame(BinkpCommand.M_NUL, "VER " + version + " binkp/1.1"));
        frames.add(new BinkpFrame(BinkpCommand.M_NUL, "TIME " + format.format(new Date())));
        frames.add(new BinkpFrame(BinkpCommand.M_ADR, config.getAddress() + "@fidonet"));
    }

    private void error(String err) {
        logger.error("lerror: " + err);
        frames.add(new BinkpFrame(BinkpCommand.M_ERR, err));
        connectionState = STATE_ERR;
    }

    /**
     * При исходящем соединении
     */
    @Override
    public void initOutgoing(Connector connector) {
        this.link = connector.getLink();
        this.connector = connector;
        this.remoteFtnAddr = new FTNAddr(link.getAddr());
        incoming = false;
        password = (link.getPass() != null) ? link.getPass() : "-";
        greet();
    }

    /**
     * При входящем соединении - создаем диджест
     */
    @Override
    public void initIncoming(Connector connector) {
        this.connector = connector;
        incoming = true;
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
            md.update(String.format("%d%d", System.currentTimeMillis(), System.nanoTime()).getBytes());
            byte[] digest = md.digest();
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                builder.append(String.format("%02x", digest[i]));
            }
            cramMD5Text = builder.toString();
            frames.add(new BinkpFrame(BinkpCommand.M_NUL, String.format("OPT CRAM-MD5-%s", cramMD5Text)));
        } catch (NoSuchAlgorithmException e) {
            cramMD5Text = null;
        }
        greet();
    }

    private BinkpCommand getCommand(int command) {
        return BinkpCommand.findCommand(command);
    }

    /**
     * Получаем фрейм из потока
     *
     * @param is
     * @return
     */
    private BinkpFrame recv(InputStream is) {
        BinkpFrame ret = null;
        DataInputStream in = new DataInputStream(is);
        try {
            if (in.available() > 0) {
                int len = in.readShort() & 0xffff;
                boolean command = ((len & 0x8000) > 0);
                len &= 0x7fff;
                if (len > 0) {
                    if (command) {
                        int cmd = in.read();
                        String arg = null;
                        if (len > 1) {
                            byte[] data = new byte[len - 1];
                            in.read(data);
                            arg = new String(data);
                        }
                        ret = new BinkpFrame(getCommand(cmd), arg);
                    } else {
                        byte[] data = new byte[len];
                        in.read(data);
                        ret = new BinkpFrame(data);
                    }
                }
            }
        } catch (IOException e) {
            logger.error("Ошибка при получении фрейма: " + e.getMessage());
        }
        return ret;
    }

    @Override
    public void avalible(InputStream is) {
        Pattern cram = Pattern.compile("^CRAM-MD5-([a-f0-9]+)$");
        BinkpFrame frame = recv(is);
        if (frame == null) {
            return;
        }
        if (connectionState < STATE_TRANSFER && !frame.isCommand()) {
            error("Unknown frame");
            return;
        }
        if (frame.isCommand() && frame.getCommand().equals(BinkpCommand.M_ERR)) {
            error(new String(frame.getData()));
            return;
        }
        if (frame.isCommand() && frame.getCommand().equals(BinkpCommand.M_BSY)) {
            logger.info("Система занята, попробуем позже");
            connectionState = STATE_END;
            return;
        }
        /**
         * M_NUL или M_ADR
         */
        if (connectionState == STATE_WAITADDR) {
            if (frame.getCommand().equals(BinkpCommand.M_NUL)) {
                String args[] = frame.getArg().split(" ");
                if (args[0].equals("OPT")) {
                    for (int i = 1; i < args.length; i++) {
                        Matcher md = cram.matcher(args[i]);
                        if (md.matches()) {
                            useCramMD5 = true;
                            cramMD5Text = md.group(1);
                            logger.info("Сервер запросил MD-режим");
                        }
                    }
                } else if (args[0].equals("VER")) {
                    if (frame.getArg().matches("^.* binkp/1\\.1$")) {
                        binkp1 = true;
                        logger.info("Версия протокола 1.1");
                    }
                }
            } else if (frame.getCommand().equals(BinkpCommand.M_ADR)) {
                boolean authorized = false;
                String[] addrs = frame.getArg().replaceFirst(" ", "").split(" ");
                if (!incoming) {
                    for (String addr : addrs) {
                        FTNAddr address = new FTNAddr(addr);
                        if (address.equals(remoteFtnAddr)) {
                            authorized = true;
                        }
                    }
                } else {
                        List<String> addrs_mod = new ArrayList<String>();
                        for (String addr : addrs) {
                            if (addr.length() < 1) {
                                continue;
                            }
                            String[] part = addr.split("@");
                            if (part.length == 1 || part[1].matches("fido.*")) {
                                addrs_mod.add(part[0]);
                            }
                        }
                        if (links.size() == 1) {
                            this.link = links.get(0);
                            this.remoteFtnAddr = new FTNAddr(link.getAddr());
                            password = (link.getPass() != null) ? link.getPass() : "-";
                            connectionState = STATE_WAITPWD;
                            authorized = true;
                        }
                }
                if (!authorized) {
                    error("Unauthorized");
                } else if (!incoming) {
                    frames.add(new BinkpFrame(BinkpCommand.M_PWD, getPassword()));
                    connectionState = STATE_WAITOK;
                }
            } else {
                error("Unknown frame");
            }
            /**
             * WAIK_OK
             */
        } else if (connectionState == STATE_WAITOK) {
            if (frame.getCommand().equals(BinkpCommand.M_OK)) {
                if (!password.equals("-")) {
                    logger.info("(C) Сессия защищена паролем (" + ((useCramMD5) ? "md5" : "plain") + ")");
                } else {
                    logger.info("(C) Сессия не защищена паролем");
                }
                connector.setLink(link);
                connectionState = STATE_TRANSFER;
            } else if (frame.getCommand().equals(BinkpCommand.M_NUL)) {
                logger.info(frame.getArg());
            } else {
                error("Unknown frame ok");
            }
            /**
             * Ждем пароля
             */
        } else if (connectionState == STATE_WAITPWD) {
            if (frame.getCommand().equals(BinkpCommand.M_PWD)) {
                boolean auth = false;
                if (frame.getArg() != null) {
                    String pw = frame.getArg();
                    if (pw.matches("^CRAM-MD5-.*")) {
                        useCramMD5 = true;
                        if (getPassword().equals(pw)) {
                            auth = true;
                        }
                    } else if (pw.equals(password)) {
                        auth = true;
                    }
                }
                if (auth) {
                    frames.add(new BinkpFrame(BinkpCommand.M_OK, (password.equals("-")) ? "insecure" : "secure"));
                    if (!password.equals("-")) {
                        logger.info("(S) Сессия защищена паролем (" + ((useCramMD5) ? "md5" : "plain") + ")");
                    } else {
                        logger.info("(S) Сессия не защищена паролем");
                    }
                    connector.setLink(link);
                    connectionState = STATE_TRANSFER;
                } else {
                    error("Bad pwd");
                }
            } else if (frame.getCommand().equals(BinkpCommand.M_NUL)) {
                logger.info(frame.getArg());
            } else {
                logger.warn("(OK) Неизвестный фрейм " + frame.toString());
            }
            /**
             * Ждем файлов
             */
        } else if (connectionState == STATE_TRANSFER) {
            if (frame.isCommand()) {
                if (frame.getCommand().equals(BinkpCommand.M_FILE)) {
                    Pattern p[] = new Pattern[] { Pattern.compile("^(\\S+) (\\d+) (\\d+) 0$"),
                            Pattern.compile("^(\\S+ \\d+ \\d+) -1$") };
                    Matcher m = p[1].matcher(frame.getArg());
                    if (m.matches()) {
                        frames.add(new BinkpFrame(BinkpCommand.M_GET, m.group(1) + " 0"));
                        return;
                    }
                    m = p[0].matcher(frame.getArg());
                    if (m.matches()) {
                        currentMessage = new Message(m.group(1), new Integer(m.group(2)));
                        currentMessageTimestamp = new Long(m.group(3));
                        currentMessageBytesLeft = (int) currentMessage.getMessageLength();
                        currentOutputStream = new ByteArrayOutputStream(currentMessageBytesLeft);
                        recvfile = true;
                        logger.info(String.format("Принимается файл: %s (%d)", currentMessage.getMessageName(),
                                currentMessage.getMessageLength()));
                    }
                } else if (frame.getCommand().equals(BinkpCommand.M_EOB)) {
                    reob = true;
                } else if (frame.getCommand().equals(BinkpCommand.M_GOT) && sendfile) {
                    Pattern p = Pattern.compile("^(\\S+) (\\d+) (\\d+)$");
                    Matcher m = p.matcher(frame.getArg());
                    if (m.matches()) {
                        String messageName = m.group(1);
                        int len = Integer.valueOf(m.group(2));
                        logger.info(String.format("Отправлен файл: %s (%d)", messageName, len));
                        totalout += len;
                        sendfile = false;
                        send = true;
                    }
                } else {
                    logger.warn("(TRANSFER) Неизвестный фрейм " + frame.toString());
                }
            } else {
                if (recvfile) {
                    byte[] data = frame.getData();
                    int len = data.length;
                    try {
                        if (currentMessageBytesLeft >= len) {
                            currentOutputStream.write(data);
                            currentMessageBytesLeft -= len;
                        } else {
                            currentOutputStream.write(data, 0, currentMessageBytesLeft);
                            currentMessageBytesLeft = 0;
                        }
                        if (currentMessageBytesLeft == 0) {
                            currentMessage.setInputStream(new ByteArrayInputStream(currentOutputStream.toByteArray()));
                            currentOutputStream.close();
                            currentOutputStream = null;
                            frames.add(new BinkpFrame(BinkpCommand.M_GOT, String.format("%s %d %d",
                                    currentMessage.getMessageName(), currentMessage.getMessageLength(),
                                    currentMessageTimestamp)));
                            received.add(currentMessage);
                            logger.info(String.format("Принят файл: %s (%d)", currentMessage.getMessageName(),
                                    currentMessage.getMessageLength()));
                            totalin += currentMessage.getMessageLength();
                            currentMessage = null;
                            recvfile = false;
                            recv = true;
                        }
                    } catch (IOException e) {
                        error("recv error " + e.getMessage());
                    }
                }
            }
        }
    }

    @Override
    public Frame[] getFrames() {
        Frame[] frames = this.frames.toArray(new Frame[0]);
        this.frames.clear();
        return frames;
    }

    @Override
    public boolean canSend() {
        return connectionState == STATE_TRANSFER && !leob;
    }

    @Override
    public boolean closed() {
        if (reob && leob && connectionState == STATE_TRANSFER) {
            if ((recv || send) && binkp1) {
                leob = false;
                reob = false;
                recv = false;
                send = false;
                logger.debug("Перезапускаем транфер");
                connector.setLink(link);
            } else {
                connectionState = STATE_END;
            }
        }
        if (connectionState == STATE_ERR) {
            logger.info(String.format("Сессия закончена с ошибками, Sb/Rb: %d/%d (%s)", totalout, totalin,
                    link.getAddr()));
            return true;
        } else if (connectionState == STATE_END) {
            logger.info(String.format("Сессия закончена успешно, Sb/Rb: %d/%d (%s)", totalout, totalin,
                    link.getAddr()));
            return true;
        }
        return false;
    }

    @Override
    public void eob() {
        if (!sendfile) {
            leob = true;
            frames.add(new BinkpFrame(BinkpCommand.M_EOB));
        }
    }

    @Override
    public void send(Message message) {
        sendfile = true;
        frames.add(new BinkpFrame(BinkpCommand.M_FILE, String.format("%s %d %d 0", message.getMessageName(),
                message.getMessageLength(), System.currentTimeMillis() / 1000)));
        logger.info(String.format("Отправляется файл: %s (%d)", message.getMessageName(), message.getMessageLength()));
        try {
            int avalible;
            while ((avalible = message.getInputStream().available()) > 0) {
                byte[] buf;
                if (avalible > 32767) {
                    buf = new byte[32767];
                } else {
                    buf = new byte[avalible];
                }
                message.getInputStream().read(buf);
                frames.add(new BinkpFrame(buf));
            }
        } catch (IOException e) {
            logger.error("Ошибка при отправке", e);
        }
    }

    @Override
    public Message[] getReceived() {
        return received.toArray(new Message[0]);
    }

    /**
     * ff -> 255
     *
     * @param s
     * @return
     */
    private static byte hex2decimal(String s) {
        String digits = "0123456789ABCDEF";
        s = s.toUpperCase();
        byte val = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int d = digits.indexOf(c);
            val = (byte) (16 * val + d);
        }
        return val;
    }

    /**
     * Получаем пароль в зависимости от cram-md5
     *
     * @return
     */
    private String getPassword() {
        MessageDigest md;
        if (password.equals("-") || !useCramMD5) {
            return password;
        }
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            return password;
        }
        byte[] text = new byte[cramMD5Text.length() / 2];
        byte[] key = password.getBytes();
        byte[] k_ipad = new byte[64];
        byte[] k_opad = new byte[64];
        for (int i = 0; i < cramMD5Text.length(); i += 2) {
            text[i / 2] = hex2decimal(cramMD5Text.substring(i, i + 2));
        }

        for (int i = 0; i < key.length; i++) {
            k_ipad[i] = key[i];
            k_opad[i] = key[i];
        }

        for (int i = 0; i < 64; i++) {
            k_ipad[i] ^= 0x36;
            k_opad[i] ^= 0x5c;
        }
        md.update(k_ipad);
        md.update(text);
        byte[] digest = md.digest();
        md.update(k_opad);
        md.update(digest);
        digest = md.digest();
        StringBuilder builder = new StringBuilder();
        builder.append("CRAM-MD5-");
        for (int i = 0; i < 16; i++) {
            builder.append(String.format("%02x", digest[i]));
        }
        return builder.toString();
    }
}
