/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.apache.mina.session;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Queue;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.apache.mina.api.IoClient;
import org.apache.mina.api.IoSession;
import org.apache.mina.api.IoSession.SessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An helper class used to manage everything related to SSL/TLS establishement
 * and management.
 * 
 * @author <a href="http://mina.apache.org">Apache MINA Project</a>
 */
public class SslHelper
{
    /** A logger for this class */
    private final static Logger LOGGER = LoggerFactory.getLogger(SslHelper.class);

    /** The SSL engine instance */
    private SSLEngine sslEngine;

    /** The SSLContext instance */
    private final SSLContext sslContext;
    
    /** The current session */
    private final IoSession session;

    /** A flag set when we process the handshake */
    private boolean handshaking = false;

    /**
     * A session attribute key that should be set to an {@link InetSocketAddress}.
     * Setting this attribute causes
     * {@link SSLContext#createSSLEngine(String, int)} to be called passing the
     * hostname and port of the {@link InetSocketAddress} to get an
     * {@link SSLEngine} instance. If not set {@link SSLContext#createSSLEngine()}
     * will be called.<br/>
     * Using this feature {@link SSLSession} objects may be cached and reused
     * when in client mode.
     *
     * @see SSLContext#createSSLEngine(String, int)
     */
    public static final String PEER_ADDRESS = "internal_peerAddress";
    
    public static final String WANT_CLIENT_AUTH = "internal_wantClientAuth";

    public static final String NEED_CLIENT_AUTH = "internal_needClientAuth";

    /** Application cleartext data to be read by application */
    private ByteBuffer appBuffer;

    /** Incoming buffer accumulating bytes read from the channel */
    private ByteBuffer accBuffer;
    
    /** An empty buffer used during the handshake phase */
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

    /** An empty buffer used during the handshake phase */
    private static final ByteBuffer HANDSHAKE_BUFFER = ByteBuffer.allocate(1024);

    /**
     * Create a new SSL Handler.
     *
     * @param session The associated session
     * @throws SSLException
     */
    public SslHelper(IoSession session, SSLContext sslContext) throws SSLException {
        this.session = session;
        this.sslContext = sslContext;
    }
    
    /**
     * @return The associated session
     */
    private IoSession getSession() {
        return session;
    }
    
    
    /**
     * @return The associated SSLEngine
     */
    private SSLEngine getEngine() {
        return sslEngine;
    }

    /**
     * Initialize the SSL handshake.
     *
     * @throws SSLException If the underlying SSLEngine handshake initialization failed
     */
    /* no qualifier */ void init() throws SSLException {
        if (sslEngine != null) {
            // We already have a SSL engine created, no need to create a new one
            return;
        }

        LOGGER.debug("{} Initializing the SSL Helper", session);

        InetSocketAddress peer = (InetSocketAddress) session.getAttribute(PEER_ADDRESS);

        // Create the SSL engine here
        if (peer == null) {
            sslEngine = sslContext.createSSLEngine();
        } else {
            sslEngine = sslContext.createSSLEngine(peer.getHostName(), peer.getPort());
        }

        // Initialize the engine in client mode if necessary
        sslEngine.setUseClientMode(session.getService() instanceof IoClient);

        // Initialize the different SslEngine modes
        if (!sslEngine.getUseClientMode()) {
            // Those parameters are only valid when in server mode
            Boolean needClientAuth = session.<Boolean>getAttribute(NEED_CLIENT_AUTH);
            Boolean wantClientAuth = session.<Boolean>getAttribute(WANT_CLIENT_AUTH);

            // The WantClientAuth supersede the NeedClientAuth, if set.
            if ((needClientAuth != null) && (needClientAuth)) {
                sslEngine.setNeedClientAuth(true);
            }
            
            if ((wantClientAuth != null) && (wantClientAuth)) {
                sslEngine.setWantClientAuth(true);
            }
        }

        if ( LOGGER.isDebugEnabled()) {
            LOGGER.debug("{} SSL Handler Initialization done.", session);
        }
    }

    private void addInBuffer(ByteBuffer buffer) {
        if (accBuffer.capacity() - accBuffer.limit() < buffer.remaining()) {
            // Increase the internal buffer
            ByteBuffer newBuffer = ByteBuffer.allocate(accBuffer.capacity() + buffer.remaining());
            
            // Copy the two buffers
            newBuffer.put(accBuffer);
            newBuffer.put(buffer);
            
            // And reset the position to the original position
            newBuffer.flip();
            
            accBuffer = newBuffer;
        } else {
            accBuffer.put(buffer);
            accBuffer.flip();
        }
    }
    
    /**
     * Process the NEED_TASK action.
     * 
     * @param engine The SSLEngine instance
     * @return The resulting HandshakeStatus
     * @throws SSLException If we've got an error while processing the tasks
     */
    private HandshakeStatus processTasks(SSLEngine engine) throws SSLException {
        Runnable runnable;
        
        while ((runnable = engine.getDelegatedTask()) != null) {
            // TODO : we may have to use a thread pool here to improve the
            // performances
            runnable.run();
        }

        HandshakeStatus hsStatus = engine.getHandshakeStatus();
        
        return hsStatus;
    }
    
    /**
     * Process the NEED_UNWRAP action. We have to read the incoming buffer, and to feed
     * the application buffer. We also have to cover the three special cases : <br/>
     * <ul>
     * <li>The incoming buffer does not contain enough data (then we need to read some
     * more and to accumulate the bytes in a temporary buffer)</li>
     * <li></li>
     * </ul>
     * 
     * @param engine
     * @param inBuffer
     * @param appBuffer
     * @return
     * @throws SSLException
     */
    private SSLEngineResult unwrap(ByteBuffer inBuffer, ByteBuffer appBuffer) throws SSLException {
        ByteBuffer tempBuffer = null;
        
        // First work with either the new incoming buffer, or the accumulating buffer
        if ((accBuffer != null) && (accBuffer.remaining() > 0)) {
            // Add the new incoming data into the local buffer
            addInBuffer(inBuffer);
            tempBuffer = this.accBuffer;
        } else {
            tempBuffer = inBuffer;
        }
        
        // Loop until we have processed the entire incoming buffer,
        // or until we have to stop
        while (true) {
            // Do the unwrapping
            SSLEngineResult result = sslEngine.unwrap(tempBuffer, appBuffer);

            switch (result.getStatus()) {
                case OK :
                    // Ok, we have unwrapped a message, return.
                    accBuffer = null;
                    
                    return result;
                    
                case BUFFER_UNDERFLOW :
                    // We need to read some more data from the channel.
                    if (this.accBuffer == null) {
                        this.accBuffer = ByteBuffer.allocate(tempBuffer.capacity() + 4096);
                        this.accBuffer.put(inBuffer);
                    }
                    
                    inBuffer.clear();
                    
                    return result;
    
                case CLOSED :
                    accBuffer = null;

                    // We have received a Close message, we
                    if (session.isConnectedSecured()) {
                        return result;
                    } else {
                        throw new IllegalStateException();
                    }
    
                case BUFFER_OVERFLOW :
                    // We have to increase the appBuffer size. In any case
                    // we aren't processing an handshake here. Read again.
                    appBuffer = ByteBuffer.allocate(appBuffer.capacity() + 4096 );
            }
        }
    }
    
    public void processRead(IoSession session, ByteBuffer readBuffer) throws SSLException {
        if (session.isConnectedSecured()) {
            // Unwrap the incoming data
            processUnwrap(session, readBuffer);
        } else {
            // Process the SSL handshake now
            processHandShake(session, readBuffer);
        }
    }
    

    private void processUnwrap(IoSession session, ByteBuffer inBuffer) throws SSLException {
        // Blind guess : once uncompressed, the resulting buffer will be 3 times bigger
        ByteBuffer appBuffer = ByteBuffer.allocate(inBuffer.limit() * 3);
        SSLEngineResult result = unwrap(inBuffer, appBuffer );

        switch (result.getStatus()) {
            case OK :
                // Ok, go through the chain now
                appBuffer.flip();
                session.getFilterChain().processMessageReceived(session, appBuffer);
                break;
                
            case CLOSED :
                processClosed( result);
                
                break;
        }
    }
    
    private void processClosed(SSLEngineResult result) throws SSLException {
        // We have received a Alert_CLosure message, we will have to do a wrap
        HandshakeStatus hsStatus = result.getHandshakeStatus();
        
        if (hsStatus == HandshakeStatus.NEED_WRAP) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("{} processing the NEED_WRAP state", session);
            }

            int capacity = sslEngine.getSession().getPacketBufferSize();
            ByteBuffer outBuffer = ByteBuffer.allocate(capacity);
            session.changeState( SessionState.CONNECTED );

            while (!sslEngine.isOutboundDone()) {
                sslEngine.wrap(EMPTY_BUFFER, outBuffer);
                outBuffer.flip();

                // Get out of the Connected state
                session.enqueueWriteRequest(outBuffer);
            }
        }
    }
    
    private boolean processHandShake(IoSession session, ByteBuffer inBuffer) throws SSLException {
        // Start the Handshake if we aren't already processing a HandShake
        // and switch to the SECURING state
        HandshakeStatus hsStatus = sslEngine.getHandshakeStatus();
        
        if ( hsStatus == HandshakeStatus.NOT_HANDSHAKING) {
            session.changeState(SessionState.SECURING);
        }

        SSLEngineResult result = null;

        // If the SSLEngine has not be started, then the status will be NOT_HANDSHAKING
        while (hsStatus != HandshakeStatus.FINISHED) {
            if (hsStatus == HandshakeStatus.NEED_TASK) {
                    hsStatus = processTasks(sslEngine);
            } else if (hsStatus == HandshakeStatus.NEED_WRAP) {
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("{} processing the NEED_WRAP state", session);
                }

                int capacity = sslEngine.getSession().getPacketBufferSize();
                ByteBuffer outBuffer = ByteBuffer.allocate(capacity);

                boolean completed = false;
                
                while (!completed) {
                    result = sslEngine.wrap(EMPTY_BUFFER, outBuffer);

                    switch (result.getStatus()) {
                        case OK :
                        case CLOSED :
                            completed = true;
                            break;
                            
                        case BUFFER_OVERFLOW :
                            ByteBuffer newBuffer = ByteBuffer.allocate(outBuffer.capacity() + 4096);
                            outBuffer.flip();
                            newBuffer.put(outBuffer);
                            outBuffer = newBuffer;
                            break;
                    }
                }

                outBuffer.flip();
                session.enqueueWriteRequest(outBuffer);
                hsStatus = result.getHandshakeStatus();
                
                if (hsStatus != HandshakeStatus.NEED_WRAP) {
                    break;
                }
            } else if ((hsStatus == HandshakeStatus.NEED_UNWRAP) || (hsStatus == HandshakeStatus.NOT_HANDSHAKING)) {
                result = unwrap(inBuffer, HANDSHAKE_BUFFER);

                if (result.getStatus() == Status.BUFFER_UNDERFLOW) {
                    // Read more data
                    break;
                } else {
                    hsStatus = result.getHandshakeStatus();
                }
            }
        }

        if (hsStatus == HandshakeStatus.FINISHED) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("{} processing the FINISHED state", session);
            }
            
            HandshakeStatus stat = sslEngine.getHandshakeStatus();

            session.changeState(SessionState.SECURED);
            handshaking = false;

            return true;
        }

        return false;
    }
    
    public DefaultWriteRequest processWrite(IoSession sessions, Object message, Queue<WriteRequest> writeQueue) {
        ByteBuffer buf = (ByteBuffer)message;
        ByteBuffer appBuffer = ByteBuffer.allocate(buf.limit() + 50);
        
        try {
            while (true) {
                SSLEngineResult result = sslEngine.wrap(buf, appBuffer);
                
                switch (result.getStatus()) {
                    case BUFFER_OVERFLOW :
                        // Increase the buffer size
                        appBuffer = ByteBuffer.allocate(appBuffer.capacity() + 4096);
                        break;
                        
                    case BUFFER_UNDERFLOW :
                    case CLOSED :
                        break;
                    case OK :
                        DefaultWriteRequest request = new DefaultWriteRequest(appBuffer);

                        writeQueue.add(request);
                        return request;
                }
                
                if ( result.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW ) {
                    // Increase the buffer size
                    appBuffer = ByteBuffer.allocate(appBuffer.capacity() + 4096);
                } else {
                }
            }
        } catch (SSLException se) {
            throw new IllegalStateException(se.getMessage());
        }
    }
}