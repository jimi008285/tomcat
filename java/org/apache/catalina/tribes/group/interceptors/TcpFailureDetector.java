/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.catalina.tribes.group.interceptors;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.HashMap;

import org.apache.catalina.tribes.Channel;
import org.apache.catalina.tribes.ChannelException;
import org.apache.catalina.tribes.ChannelException.FaultyMember;
import org.apache.catalina.tribes.ChannelMessage;
import org.apache.catalina.tribes.Member;
import org.apache.catalina.tribes.RemoteProcessException;
import org.apache.catalina.tribes.group.ChannelInterceptorBase;
import org.apache.catalina.tribes.group.InterceptorPayload;
import org.apache.catalina.tribes.io.ChannelData;
import org.apache.catalina.tribes.io.XByteBuffer;
import org.apache.catalina.tribes.membership.Membership;
import org.apache.catalina.tribes.membership.StaticMember;
import org.apache.catalina.tribes.util.StringManager;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;

/**
 * The TcpFailureDetector is a useful interceptor that adds reliability to the membership layer.
 * <p>
 * If the network is busy, or the system is busy so that the membership receiver thread is not getting enough time to
 * update its table, members can be &quot;timed out&quot; This failure detector will intercept the memberDisappeared
 * message(unless its a true shutdown message) and connect to the member using TCP.
 * <p>
 * The TcpFailureDetector works in two ways:
 * <ol>
 * <li>It intercepts memberDisappeared events</li>
 * <li>It catches send errors</li>
 * </ol>
 */
public class TcpFailureDetector extends ChannelInterceptorBase implements TcpFailureDetectorMBean {

    private static final Log log = LogFactory.getLog(TcpFailureDetector.class);
    protected static final StringManager sm = StringManager.getManager(TcpFailureDetector.class);

    protected static final byte[] TCP_FAIL_DETECT = new byte[] { 79, -89, 115, 72, 121, -126, 67, -55, -97, 111, -119,
            -128, -95, 91, 7, 20, 125, -39, 82, 91, -21, -15, 67, -102, -73, 126, -66, -113, -127, 103, 30, -74, 55, 21,
            -66, -121, 69, 126, 76, -88, -65, 10, 77, 19, 83, 56, 21, 50, 85, -10, -108, -73, 58, -6, 64, 120, -111, 4,
            125, -41, 114, -124, -64, -43 };

    protected long connectTimeout = 1000;// 1 second default

    protected boolean performSendTest = true;

    protected boolean performReadTest = false;

    protected long readTestTimeout = 5000;// 5 seconds

    protected Membership membership = null;

    protected final HashMap<Member,Long> removeSuspects = new HashMap<>();

    protected final HashMap<Member,Long> addSuspects = new HashMap<>();

    protected int removeSuspectsTimeout = 300; // 5 minutes

    @Override
    public void sendMessage(Member[] destination, ChannelMessage msg, InterceptorPayload payload)
            throws ChannelException {
        try {
            super.sendMessage(destination, msg, payload);
        } catch (ChannelException cx) {
            FaultyMember[] mbrs = cx.getFaultyMembers();
            for (FaultyMember mbr : mbrs) {
                if (mbr.getCause() != null && (!(mbr.getCause() instanceof RemoteProcessException))) {// RemoteProcessException's
                                                                                                      // are ok
                    this.memberDisappeared(mbr.getMember());
                } // end if
            } // for
            throw cx;
        }
    }

    @Override
    public void messageReceived(ChannelMessage msg) {
        // catch incoming
        boolean process = true;
        if (okToProcess(msg.getOptions())) {
            // check to see if it is a testMessage, if so, process = false
            process = ((msg.getMessage().getLength() != TCP_FAIL_DETECT.length) ||
                    (!Arrays.equals(TCP_FAIL_DETECT, msg.getMessage().getBytes())));
        } // end if

        // ignore the message, it doesn't have the flag set
        if (process) {
            super.messageReceived(msg);
        } else if (log.isDebugEnabled()) {
            log.debug(sm.getString("tcpFailureDetector.receivedPacket", msg));
        }
    }// messageReceived


    @Override
    public void memberAdded(Member member) {
        if (membership == null) {
            setupMembership();
        }
        boolean notify = false;
        synchronized (membership) {
            if (removeSuspects.containsKey(member)) {
                // previously marked suspect, system below picked up the member again
                removeSuspects.remove(member);
            } else if (membership.getMember(member) == null) {
                // if we add it here, then add it upwards too
                // check to see if it is alive
                if (memberAlive(member)) {
                    membership.memberAlive(member);
                    addSuspects.remove(member);
                    notify = true;
                } else {
                    if (member instanceof StaticMember) {
                        addSuspects.put(member, Long.valueOf(System.currentTimeMillis()));
                    }
                }
            }
        }
        if (notify) {
            super.memberAdded(member);
        }
    }

    @Override
    public void memberDisappeared(Member member) {
        if (membership == null) {
            setupMembership();
        }
        boolean shutdown = Arrays.equals(member.getCommand(), Member.SHUTDOWN_PAYLOAD);
        if (shutdown) {
            synchronized (membership) {
                if (!membership.contains(member)) {
                    return;
                }
                membership.removeMember(member);
                removeSuspects.remove(member);
                if (member instanceof StaticMember) {
                    addSuspects.put(member, Long.valueOf(System.currentTimeMillis()));
                }
            }
            super.memberDisappeared(member);
        } else {
            boolean notify = false;
            if (log.isInfoEnabled()) {
                log.info(sm.getString("tcpFailureDetector.memberDisappeared.verify", member));
            }
            synchronized (membership) {
                if (!membership.contains(member)) {
                    if (log.isInfoEnabled()) {
                        log.info(sm.getString("tcpFailureDetector.already.disappeared", member));
                    }
                    return;
                }
                // check to see if the member really is gone
                if (!memberAlive(member)) {
                    // not correct, we need to maintain the map
                    membership.removeMember(member);
                    removeSuspects.remove(member);
                    if (member instanceof StaticMember) {
                        addSuspects.put(member, Long.valueOf(System.currentTimeMillis()));
                    }
                    notify = true;
                } else {
                    // add the member as suspect
                    removeSuspects.put(member, Long.valueOf(System.currentTimeMillis()));
                }
            }
            if (notify) {
                if (log.isInfoEnabled()) {
                    log.info(sm.getString("tcpFailureDetector.member.disappeared", member));
                }
                super.memberDisappeared(member);
            } else {
                if (log.isInfoEnabled()) {
                    log.info(sm.getString("tcpFailureDetector.still.alive", member));
                }
            }
        }
    }

    @Override
    public boolean hasMembers() {
        if (membership == null) {
            setupMembership();
        }
        return membership.hasMembers();
    }

    @Override
    public Member[] getMembers() {
        if (membership == null) {
            setupMembership();
        }
        return membership.getMembers();
    }

    @Override
    public Member getMember(Member mbr) {
        if (membership == null) {
            setupMembership();
        }
        return membership.getMember(mbr);
    }

    @Override
    public Member getLocalMember(boolean incAlive) {
        return super.getLocalMember(incAlive);
    }

    @Override
    public void heartbeat() {
        super.heartbeat();
        checkMembers(false);
    }

    @Override
    public void checkMembers(boolean checkAll) {
        try {
            if (membership == null) {
                setupMembership();
            }
            synchronized (membership) {
                if (!checkAll) {
                    performBasicCheck();
                } else {
                    performForcedCheck();
                }
            }
        } catch (Exception x) {
            log.warn(sm.getString("tcpFailureDetector.heartbeat.failed"), x);
        }
    }

    protected void performForcedCheck() {
        // update all alive times
        Member[] members = super.getMembers();
        for (int i = 0; members != null && i < members.length; i++) {
            if (memberAlive(members[i])) {
                if (membership.memberAlive(members[i])) {
                    super.memberAdded(members[i]);
                }
                addSuspects.remove(members[i]);
            } else {
                if (membership.getMember(members[i]) != null) {
                    membership.removeMember(members[i]);
                    removeSuspects.remove(members[i]);
                    if (members[i] instanceof StaticMember) {
                        addSuspects.put(members[i], Long.valueOf(System.currentTimeMillis()));
                    }
                    super.memberDisappeared(members[i]);
                }
            } // end if
        } // for

    }

    protected void performBasicCheck() {
        // update all alive times
        Member[] members = super.getMembers();
        for (int i = 0; members != null && i < members.length; i++) {
            if (addSuspects.containsKey(members[i]) && membership.getMember(members[i]) == null) {
                // avoid temporary adding member.
                continue;
            }
            if (membership.memberAlive(members[i])) {
                // we don't have this one in our membership, check to see if the member is alive
                if (memberAlive(members[i])) {
                    log.warn(sm.getString("tcpFailureDetector.performBasicCheck.memberAdded", members[i]));
                    super.memberAdded(members[i]);
                } else {
                    membership.removeMember(members[i]);
                } // end if
            } // end if
        } // for

        // check suspect members if they are still alive,
        // if not, simply issue the memberDisappeared message
        Member[] keys = removeSuspects.keySet().toArray(new Member[0]);
        for (Member m : keys) {
            if (membership.getMember(m) != null && (!memberAlive(m))) {
                membership.removeMember(m);
                if (m instanceof StaticMember) {
                    addSuspects.put(m, Long.valueOf(System.currentTimeMillis()));
                }
                super.memberDisappeared(m);
                removeSuspects.remove(m);
                if (log.isInfoEnabled()) {
                    log.info(sm.getString("tcpFailureDetector.suspectMember.dead", m));
                }
            } else {
                if (removeSuspectsTimeout > 0) {
                    long timeNow = System.currentTimeMillis();
                    int timeIdle = (int) ((timeNow - removeSuspects.get(m).longValue()) / 1000L);
                    if (timeIdle > removeSuspectsTimeout) {
                        removeSuspects.remove(m); // remove suspect member
                    }
                }
            }
        }

        // check add suspects members if they are alive now,
        // if they are, simply issue the memberAdded message
        keys = addSuspects.keySet().toArray(new Member[0]);
        for (Member m : keys) {
            if (membership.getMember(m) == null && (memberAlive(m))) {
                membership.memberAlive(m);
                super.memberAdded(m);
                addSuspects.remove(m);
                if (log.isInfoEnabled()) {
                    log.info(sm.getString("tcpFailureDetector.suspectMember.alive", m));
                }
            } // end if
        }
    }

    protected synchronized void setupMembership() {
        if (membership == null) {
            membership = new Membership(super.getLocalMember(true));
        }

    }

    protected boolean memberAlive(Member mbr) {
        return memberAlive(mbr, TCP_FAIL_DETECT, performSendTest, performReadTest, readTestTimeout, connectTimeout,
                getOptionFlag());
    }

    protected boolean memberAlive(Member mbr, byte[] msgData, boolean sendTest, boolean readTest, long readTimeout,
            long conTimeout, int optionFlag) {
        // could be a shutdown notification
        if (Arrays.equals(mbr.getCommand(), Member.SHUTDOWN_PAYLOAD)) {
            return false;
        }

        try (Socket socket = new Socket()) {
            InetAddress ia = InetAddress.getByAddress(mbr.getHost());
            InetSocketAddress addr = new InetSocketAddress(ia, mbr.getPort());
            socket.setSoTimeout((int) readTimeout);
            socket.connect(addr, (int) conTimeout);
            if (sendTest) {
                ChannelData data = new ChannelData(true);
                data.setAddress(getLocalMember(false));
                data.setMessage(new XByteBuffer(msgData, false));
                data.setTimestamp(System.currentTimeMillis());
                int options = optionFlag | Channel.SEND_OPTIONS_BYTE_MESSAGE;
                if (readTest) {
                    options = (options | Channel.SEND_OPTIONS_USE_ACK);
                } else {
                    options = (options & (~Channel.SEND_OPTIONS_USE_ACK));
                }
                data.setOptions(options);
                byte[] message = XByteBuffer.createDataPackage(data);
                socket.getOutputStream().write(message);
                if (readTest) {
                    int length = socket.getInputStream().read(message);
                    return length > 0;
                }
            } // end if
            return true;
        } catch (SocketTimeoutException | ConnectException | NoRouteToHostException noop) {
            // do nothing, we couldn't connect
        } catch (Exception x) {
            log.error(sm.getString("tcpFailureDetector.failureDetection.failed", mbr), x);
        }
        return false;
    }

    @Override
    public long getReadTestTimeout() {
        return readTestTimeout;
    }

    @Override
    public boolean getPerformSendTest() {
        return performSendTest;
    }

    @Override
    public boolean getPerformReadTest() {
        return performReadTest;
    }

    @Override
    public long getConnectTimeout() {
        return connectTimeout;
    }

    @Override
    public int getRemoveSuspectsTimeout() {
        return removeSuspectsTimeout;
    }

    @Override
    public void setPerformReadTest(boolean performReadTest) {
        this.performReadTest = performReadTest;
    }

    @Override
    public void setPerformSendTest(boolean performSendTest) {
        this.performSendTest = performSendTest;
    }

    @Override
    public void setReadTestTimeout(long readTestTimeout) {
        this.readTestTimeout = readTestTimeout;
    }

    @Override
    public void setConnectTimeout(long connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    @Override
    public void setRemoveSuspectsTimeout(int removeSuspectsTimeout) {
        this.removeSuspectsTimeout = removeSuspectsTimeout;
    }

}