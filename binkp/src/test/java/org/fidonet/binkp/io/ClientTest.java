/******************************************************************************
 * Copyright (c) 2013, Vladimir Kravets                                       *
 * All rights reserved.                                                       *
 *                                                                            *
 * Redistribution and use in source and binary forms, with or without         *
 * modification, are permitted provided that the following conditions are     *
 * met: Redistributions of source code must retain the above copyright notice, 
 * this list of conditions and the following disclaimer.                      *
 * Redistributions in binary form must reproduce the above copyright notice,  *
 * this list of conditions and the following disclaimer in the documentation  *
 * and/or other materials provided with the distribution.                     *
 * Neither the name of the Fido4Java nor the names of its contributors        *
 * may be used to endorse or promote products derived from this software      *
 * without specific prior written permission.                                 *
 *                                                                            *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" 
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,      *
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR     *
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR          *
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,      *
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,        *
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; 
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,   *
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR    *
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,             *
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.                         *
 ******************************************************************************/

package org.fidonet.binkp.io;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.core.service.DefaultTransportMetadata;
import org.apache.mina.core.session.IoSessionConfig;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.filter.codec.ProtocolCodecSession;
import org.apache.mina.filter.codec.ProtocolDecoder;
import org.fidonet.binkp.Connector;
import org.fidonet.binkp.LinksInfo;
import org.fidonet.binkp.SessionContext;
import org.fidonet.binkp.SessionState;
import org.fidonet.binkp.codec.BinkDataCodecFactory;
import org.fidonet.binkp.codec.BinkDataDecoder;
import org.fidonet.binkp.config.ServerRole;
import org.fidonet.binkp.config.StationConfig;
import org.fidonet.binkp.events.ConnectedEvent;
import org.fidonet.binkp.events.DisconnectedEvent;
import org.fidonet.binkp.events.FileReceivedEvent;
import org.fidonet.binkp.handler.BinkSessionHandler;
import org.fidonet.events.Event;
import org.fidonet.events.EventHandler;
import org.fidonet.types.FTNAddr;
import org.fidonet.types.Link;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.SocketAddress;

/**
 * Created by IntelliJ IDEA.
 * Author: Vladimir Kravets
 * E-Mail: vova.kravets@gmail.com
 * Date: 9/26/12
 * Time: 4:57 PM
 */
public class ClientTest {

    private class ClientMock extends Connector implements Runnable {

        private ProtocolCodecSession session;
        private SessionContext sessionContext;

        public ClientMock(SessionContext sessionContext) {
            this.sessionContext = sessionContext;
        }

        @Override
        public void run(SessionContext sessionContext) throws Exception {
            session = new ProtocolCodecSession();
            session.getFilterChain().addFirst("codec", new ProtocolCodecFilter(new BinkDataCodecFactory()));
            session.setHandler(new BinkSessionHandler(sessionContext, getEventBus()));
            session.setTransportMetadata(new DefaultTransportMetadata(
                    "mina", "dummy", false, true,
                    SocketAddress.class, IoSessionConfig.class, Object.class));
            sessionContext.setServerRole(ServerRole.CLIENT);
            session.getHandler().sessionOpened(session);
            sessionContext.setState(SessionState.STATE_IDLE);
        }

        @Override
        public void stop() {
            session.close();
        }

        public ProtocolCodecSession getSession() {
            return session;
        }

        public void writeStreamToSession(InputStream stream) throws Exception {
            int bufSize = 10;
            int size = 0;
            byte[] bytes = new byte[bufSize];
            ProtocolDecoder decoder = new BinkDataDecoder();
            while ((size = stream.read(bytes)) != -1) {
                IoBuffer buf = IoBuffer.allocate(size);
                buf.put(bytes, 0, size);
                buf.flip();
                decoder.decode(session, buf, session.getDecoderOutput());
                Thread.sleep(100);
            }
            Thread.sleep(1000);
            sessionContext.setState(SessionState.STATE_END);
        }

        @Override
        public void run() {
            try {
                run(sessionContext);
                InputStream recvRawData = ClientMock.class.getClassLoader().getResourceAsStream("recv_stream.bin");
                Runnable recvMessage = new Runnable() {
                    @Override
                    public void run() {
                        while (!sessionContext.getState().equals(SessionState.STATE_END)) {
                            while (!session.getDecoderOutputQueue().isEmpty()) {
                                Object msg = session.getDecoderOutputQueue().poll();
                                try {
                                    session.getHandler().messageReceived(session, msg);
                                } catch (Exception e) {
                                    e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                                }
                            }
                            try {
                                Thread.sleep(100);
                            } catch (InterruptedException e) {
                                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                            }
                        }
                    }
                };
                Thread recvMessageThread = new Thread(recvMessage);
                recvMessageThread.start();
                ClientMock.this.writeStreamToSession(recvRawData);
            } catch (Exception e) {
                e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
            }
        }

        public void registerEvent(Class<? extends Event> eventClass, EventHandler<? extends Event> eventHandler) {
            getEventBus().register(eventClass, eventHandler);
        }
    }

    private Boolean connected = false;
    private Boolean disconnected = false;
    private Boolean fileReceived = false;

    @Test
    public void testBaseFlow() throws InterruptedException {
        StationConfig config = new StationConfig("Test Station", "Vasya Pupkin", "Odessa, Ukraine", "BINKP", "2:467/110.113");
        LinksInfo linksInfo = new LinksInfo(new Link(new FTNAddr("2:467/110.113"), new FTNAddr("2:467/110"), "pass_i_f", "localhost", 24554));
        SessionContext sessionContext = new SessionContext(config, linksInfo);
        sessionContext.getStationConfig().setCryptMode(true);
        ClientMock clientMock = new ClientMock(sessionContext);
        registerEvents(clientMock);
        Thread thread = new Thread(clientMock);
        thread.start();
        thread.join();
        FileInfo fileInfo = sessionContext.getRecvFiles().peek().getInfo();
        Assert.assertEquals("0000FF8F.WE5", fileInfo.getName());
        Assert.assertEquals(766, fileInfo.getSize());
        Assert.assertEquals(true, fileInfo.isFinished());
        Assert.assertEquals(true, connected);
        Assert.assertEquals(true, disconnected);
        Assert.assertEquals(true, fileReceived);
    }

    private void registerEvents(ClientMock client) {
        client.registerEvent(ConnectedEvent.class, new EventHandler<ConnectedEvent>() {
            @Override
            public void onEventHandle(ConnectedEvent event) {
                connected = true;
            }
        });

        client.registerEvent(DisconnectedEvent.class, new EventHandler<DisconnectedEvent>() {
            @Override
            public void onEventHandle(DisconnectedEvent event) {
                disconnected = true;
            }
        });

        client.registerEvent(FileReceivedEvent.class, new EventHandler<FileReceivedEvent>() {
            @Override
            public void onEventHandle(FileReceivedEvent event) {
                fileReceived = true;
                FileInfo expectedInfo = new FileInfo("0000FF8F.WE5", 766, 1348664500L);
                Assert.assertEquals(expectedInfo, event.getFile().getInfo());
                Assert.assertEquals(true, event.getFile().getInfo().isFinished());
                ByteArrayOutputStream outStream = (ByteArrayOutputStream) event.getFile().getStream();
                Assert.assertEquals(766, outStream.toByteArray().length);
            }
        });
    }

}
