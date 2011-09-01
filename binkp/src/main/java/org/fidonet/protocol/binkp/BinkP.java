package org.fidonet.protocol.binkp;

import org.fidonet.config.Config;
import org.fidonet.types.Link;

import java.io.IOException;
import java.net.Socket;

/**
 * Created by IntelliJ IDEA.
 * User: toch
 * Date: 16.02.11
 * Time: 12:52
 */
public class BinkP {

    public BinkP() {

    }

    public SessionResult Poll(Link link, Config config) {
        Socket clientsock = null;
        try {
            clientsock = new Socket("bbs.agooga.ru", 24554);
        } catch (IOException e) {
            e.printStackTrace();
        }

        Session session = new Session(clientsock, link, config);

        session.run();

        return session.getResult();

    }

}
