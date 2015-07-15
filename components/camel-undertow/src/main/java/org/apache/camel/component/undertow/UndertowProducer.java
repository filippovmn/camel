/**
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
package org.apache.camel.component.undertow;

import java.io.IOException;
import java.nio.ByteBuffer;

import io.undertow.client.ClientCallback;
import io.undertow.client.ClientConnection;
import io.undertow.client.ClientExchange;
import io.undertow.client.ClientRequest;
import io.undertow.client.UndertowClient;
import io.undertow.util.Headers;
import io.undertow.util.Protocols;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.TypeConverter;
import org.apache.camel.impl.DefaultProducer;
import org.apache.camel.util.ExchangeHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.BufferAllocator;
import org.xnio.ByteBufferSlicePool;
import org.xnio.IoFuture;
import org.xnio.OptionMap;
import org.xnio.Xnio;
import org.xnio.XnioWorker;

/**
 * The Undertow producer.
 *
 * The implementation of Producer is considered as experimental. The Undertow client classes are not thread safe,
 * their purpose is for the reverse proxy usage inside Undertow itself. This may change in the future versions and
 * general purpose HTTP client wrapper will be added. Therefore this Producer may be changed too.
 */
public class UndertowProducer extends DefaultProducer {
    private static final Logger LOG = LoggerFactory.getLogger(UndertowProducer.class);
    private UndertowEndpoint endpoint;

    public UndertowProducer(UndertowEndpoint endpoint) {
        super(endpoint);
        this.endpoint = endpoint;
    }

    @Override
    public UndertowEndpoint getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(UndertowEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    // TODO: use async routing engine

    @Override
    public void process(Exchange exchange) throws Exception {
        final UndertowClient client = UndertowClient.getInstance();
        XnioWorker worker = Xnio.getInstance().createWorker(OptionMap.EMPTY);
        IoFuture<ClientConnection> connect = client.connect(endpoint.getHttpURI(), worker, new ByteBufferSlicePool(BufferAllocator.DIRECT_BYTE_BUFFER_ALLOCATOR, 8192, 8192 * 8192), OptionMap.EMPTY);

        ClientRequest request = new ClientRequest();
        request.setProtocol(Protocols.HTTP_1_1);

        Object body = getRequestBody(request, exchange);

        TypeConverter tc = endpoint.getCamelContext().getTypeConverter();
        ByteBuffer bodyAsByte = tc.convertTo(ByteBuffer.class, body);

        if (body != null) {
            request.getRequestHeaders().put(Headers.CONTENT_LENGTH, bodyAsByte.array().length);
        }

        connect.get().sendRequest(request, new UndertowProducerCallback(bodyAsByte, exchange));
    }

    private Object getRequestBody(ClientRequest request, Exchange camelExchange) {
        Object result;
        result = endpoint.getUndertowHttpBinding().toHttpRequest(request, camelExchange.getIn());
        return result;
    }

    /**
     * Everything important happens in callback
     */
    private class UndertowProducerCallback implements ClientCallback<ClientExchange> {

        private ByteBuffer body;
        private Exchange camelExchange;

        public UndertowProducerCallback(ByteBuffer body, Exchange camelExchange) {
            this.body = body;
            this.camelExchange = camelExchange;
        }

        @Override
        public void completed(ClientExchange clientExchange) {
            clientExchange.setResponseListener(new ClientCallback<ClientExchange>() {
                @Override
                public void completed(ClientExchange clientExchange) {
                    Message message = null;
                    try {
                        message = endpoint.getUndertowHttpBinding().toCamelMessage(clientExchange, camelExchange);
                    } catch (Exception e) {
                        camelExchange.setException(e);
                    }
                    if (ExchangeHelper.isOutCapable(camelExchange)) {
                        camelExchange.setOut(message);
                    } else {
                        camelExchange.setIn(message);
                    }

                }

                @Override
                public void failed(IOException e) {
                    camelExchange.setException(e);
                }
            });
            try {
                //send body if exists
                if (body != null) {
                    clientExchange.getRequestChannel().write(body);
                }
            } catch (IOException e) {
                camelExchange.setException(e);
            }
        }

        @Override
        public void failed(IOException e) {
            camelExchange.setException(e);
        }
    }

}
