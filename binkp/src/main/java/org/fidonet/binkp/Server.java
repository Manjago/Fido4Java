package org.fidonet.binkp;

import org.apache.mina.core.service.IoService;
import org.apache.mina.core.service.IoServiceListener;
import org.apache.mina.core.session.IdleStatus;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.filter.codec.ProtocolCodecFilter;
import org.apache.mina.transport.socket.nio.NioSocketAcceptor;
import org.fidonet.binkp.codec.BinkDataCodecFactory;
import org.fidonet.binkp.config.ServerRole;
import org.fidonet.binkp.handler.BinkSessionHandler;

/**
 * Created by IntelliJ IDEA.
 * Author: Vladimir Kravets
 * E-Mail: vova.kravets@gmail.com
 * Date: 9/19/12
 * Time: 2:02 PM
 */
public class Server extends Connector{

    private NioSocketAcceptor acceptor;
    private int port = Connector.BINK_PORT;

    public Server() { }

    public Server(int port) {
        this.port = port;
    }

    @Override
    public void run(final SessionContext context) throws Exception {
        acceptor = new NioSocketAcceptor();
        acceptor.getFilterChain().addLast("codec", new ProtocolCodecFilter(new BinkDataCodecFactory()));
        acceptor.setHandler(new BinkSessionHandler());
        acceptor.addListener(new IoServiceListener() {
            @Override
            public void serviceActivated(IoService ioService) throws Exception {
                // server is started
            }

            @Override
            public void serviceIdle(IoService ioService, IdleStatus idleStatus) throws Exception {
                // server waiting the connection
            }

            @Override
            public void serviceDeactivated(IoService ioService) throws Exception {
                Server.this.stop(context);
            }

            @Override
            public void sessionCreated(IoSession session) throws Exception {
                // got new connection to server
                SessionContext sessionContext = new SessionContext(context);
                sessionContext.setServerRole(ServerRole.SERVER);
                session.setAttribute(SessionContext.SESSION_CONTEXT_KEY, sessionContext);
                //session.getRemoteAddress()
            }

            @Override
            public void sessionDestroyed(IoSession session) throws Exception {
                SessionContext context = (SessionContext)session.getAttribute(SessionContext.SESSION_CONTEXT_KEY);
                if (context.getState().equals(SessionState.STATE_ERR)) {
                    // TODO log or out error message
                    context.getLastErrorMessage();
                }
                session.removeAttribute(SessionContext.SESSION_CONTEXT_KEY);
            }
        });
        acceptor.bind();
    }

    @Override
    public void stop(SessionContext context) {
        acceptor.dispose();
    }
}
