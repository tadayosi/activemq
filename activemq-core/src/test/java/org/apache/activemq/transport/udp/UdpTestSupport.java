/**
 *
 * Copyright 2005-2006 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.transport.udp;

import org.apache.activemq.command.ActiveMQDestination;
import org.apache.activemq.command.ActiveMQQueue;
import org.apache.activemq.command.ActiveMQTextMessage;
import org.apache.activemq.command.Command;
import org.apache.activemq.command.ConsumerInfo;
import org.apache.activemq.command.Response;
import org.apache.activemq.command.WireFormatInfo;
import org.apache.activemq.transport.Transport;
import org.apache.activemq.transport.TransportAcceptListener;
import org.apache.activemq.transport.TransportListener;
import org.apache.activemq.transport.TransportServer;

import javax.jms.MessageNotWriteableException;

import java.io.IOException;

import junit.framework.TestCase;

/**
 * 
 * @version $Revision$
 */
public abstract class UdpTestSupport extends TestCase implements TransportListener {

    protected Transport producer;
    protected Transport consumer;

    protected Object lock = new Object();
    protected Command receivedCommand;
    protected TransportServer server;
    protected boolean large;

    public void testSendingSmallMessage() throws Exception {
        ConsumerInfo expected = new ConsumerInfo();
        expected.setSelector("Cheese");
        expected.setExclusive(true);
        expected.setExclusive(true);
        expected.setPrefetchSize(3456);

        try {
            System.out.println("About to send: " + expected);
            producer.oneway(expected);

            Command received = assertCommandReceived();
            assertTrue("Should have received a ConsumerInfo but was: " + received, received instanceof ConsumerInfo);
            ConsumerInfo actual = (ConsumerInfo) received;
            assertEquals("Selector", expected.getSelector(), actual.getSelector());
            assertEquals("isExclusive", expected.isExclusive(), actual.isExclusive());
            assertEquals("getPrefetchSize", expected.getPrefetchSize(), actual.getPrefetchSize());
        }
        catch (Exception e) {
            System.out.println("Caught: " + e);
            e.printStackTrace();
            fail("Failed to send to transport: " + e);
        }
    }

    public void testSendingMediumMessage() throws Exception {
        String text = createMessageBodyText(4 * 105);
        ActiveMQDestination destination = new ActiveMQQueue("Foo.Bar.Medium");
        assertSendTextMessage(destination, text);
    }

    public void testSendingLargeMessage() throws Exception {
        String text = createMessageBodyText(4 * 1024);
        ActiveMQDestination destination = new ActiveMQQueue("Foo.Bar.Large");
        assertSendTextMessage(destination, text);
    }

    protected void assertSendTextMessage(ActiveMQDestination destination, String text) throws MessageNotWriteableException {
        large = true;

        ActiveMQTextMessage expected = new ActiveMQTextMessage();

        expected.setText(text);
        expected.setDestination(destination);

        try {
            System.out.println("About to send message of type: " + expected.getClass());
            producer.oneway(expected);

            Command received = assertCommandReceived();
            assertTrue("Should have received a ActiveMQTextMessage but was: " + received, received instanceof ActiveMQTextMessage);
            ActiveMQTextMessage actual = (ActiveMQTextMessage) received;

            assertEquals("getDestination", expected.getDestination(), actual.getDestination());
            assertEquals("getText", expected.getText(), actual.getText());
            
            System.out.println("Received text message with: " + actual.getText().length() + " character(s)");
        }
        catch (Exception e) {
            System.out.println("Caught: " + e);
            e.printStackTrace();
            fail("Failed to send to transport: " + e);
        }
    }

    protected String createMessageBodyText(int loopSize) {
        StringBuffer buffer = new StringBuffer();
        for (int i = 0; i < loopSize; i++) {
            buffer.append("0123456789");
        }
        return buffer.toString();
    }

    protected void setUp() throws Exception {
        server = createServer();
        if (server != null) {
            server.setAcceptListener(new TransportAcceptListener() {

                public void onAccept(Transport transport) {
                    consumer = transport;
                    consumer.setTransportListener(UdpTestSupport.this);
                    try {
                        consumer.start();
                    }
                    catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }

                public void onAcceptError(Exception error) {
                }
            });
            server.start();
        }

        consumer = createConsumer();
        if (consumer != null) {
            consumer.setTransportListener(this);
            consumer.start();
        }

        producer = createProducer();
        producer.setTransportListener(new TransportListener() {
            public void onCommand(Command command) {
                System.out.println("Producer received: " + command);
            }

            public void onException(IOException error) {
                System.out.println("Producer exception: " + error);
            }

            public void transportInterupted() {
            }

            public void transportResumed() {
            }
        });

        producer.start();
    }

    protected void tearDown() throws Exception {
        if (producer != null) {
            producer.stop();
        }
        if (consumer != null) {
            consumer.stop();
        }
        if (server != null) {
            server.stop();
        }
    }

    public void onCommand(Command command) {
        if (command instanceof WireFormatInfo) {
            System.out.println("Got WireFormatInfo: " + command);
        }
        else {
            if (command.isResponseRequired()) {
                // lets send a response back...
                sendResponse(command);

            }
            if (large) {
                System.out.println("### Received command: " + command.getClass() + " with id: " + command.getCommandId());
            }
            else {
                System.out.println("### Received command: " + command);
            }

            synchronized (lock) {
                receivedCommand = command;
                lock.notifyAll();
            }
        }
    }

    protected void sendResponse(Command command) {
        Response response = new Response();                
        response.setCorrelationId(command.getCommandId());
        try {
            consumer.oneway(response);
        }
        catch (IOException e) {
            System.out.println("Caught: " + e);
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public void onException(IOException error) {
        System.out.println("### Received error: " + error);
    }

    public void transportInterupted() {
        System.out.println("### Transport interrupted");
    }

    public void transportResumed() {
        System.out.println("### Transport resumed");
    }

    protected Command assertCommandReceived() throws InterruptedException {
        Command answer = null;
        synchronized (lock) {
            answer = receivedCommand;
            if (answer == null) {
                lock.wait(5000);
            }
            answer = receivedCommand;
        }

        assertNotNull("Should have received a Command by now!", answer);
        return answer;
    }

    protected abstract Transport createConsumer() throws Exception;

    protected abstract Transport createProducer() throws Exception;

    protected TransportServer createServer() throws Exception {
        return null;
    }

}
