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
package org.fusesource.camel.component.salesforce.internal.client;

import org.eclipse.jetty.client.ContentExchange;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpEventListenerWrapper;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.util.StringUtil;
import org.fusesource.camel.component.salesforce.api.SalesforceException;
import org.fusesource.camel.component.salesforce.internal.SalesforceSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public abstract class AbstractClientBase {

    protected final Logger LOG = LoggerFactory.getLogger(getClass());

    protected static final String APPLICATION_JSON_UTF8 = "application/json;charset=utf-8";
    protected static final String APPLICATION_XML_UTF8 = "application/xml;charset=utf-8";

    protected final HttpClient httpClient;
    protected final SalesforceSession session;
    protected final String version;

    protected String accessToken;
    protected String instanceUrl;

    public AbstractClientBase(String version,
                              SalesforceSession session, HttpClient httpClient) throws SalesforceException {

        this.version = version;
        this.session = session;
        this.httpClient = httpClient;

        // local cache
        // TODO could probably be replaced with a TokenListener to auto-update all clients on token refresh
        this.accessToken = session.getAccessToken();
        if (accessToken == null) {
            // lazy login here!
            this.accessToken = session.login(this.accessToken);
        }
        this.instanceUrl = session.getInstanceUrl();
    }

    protected SalesforceExchange getContentExchange(String method, String url) {
        SalesforceExchange get = new SalesforceExchange();
        get.setMethod(method);
        get.setURL(url);
        get.setClient(this);
        get.setSession(session);
        get.setAccessToken(accessToken);
        return get;
    }

    protected interface ClientResponseCallback {
        void onResponse(InputStream response, SalesforceException ex);
    }

    protected void doHttpRequest(final ContentExchange request, final ClientResponseCallback callback) {

        // use HttpEventListener for lifecycle events
        request.setEventListener(new HttpEventListenerWrapper(request.getEventListener(), true) {

            public String reason;
            public int retries = 0;

            @Override
            public void onConnectionFailed(Throwable ex) {
                super.onConnectionFailed(ex);
                callback.onResponse(null,
                    new SalesforceException("Connection error: " + ex.getMessage(), ex));
            }

            @Override
            public void onException(Throwable ex) {
                super.onException(ex);
                callback.onResponse(null,
                    new SalesforceException("Unexpected exception: " + ex.getMessage(), ex));
            }

            @Override
            public void onExpire() {
                super.onExpire();
                callback.onResponse(null,
                    new SalesforceException("Request expired", null));
            }

            @Override
            public void onResponseComplete() throws IOException {
                super.onResponseComplete();

                final int responseStatus = request.getResponseStatus();
                if (responseStatus < HttpStatus.OK_200 || responseStatus >= HttpStatus.MULTIPLE_CHOICES_300) {
                    final String msg = String.format("Error {%s:%s} executing {%s:%s}",
                        responseStatus, reason,
                        request.getMethod(), request.getRequestURI());
                    final SalesforceException exception = new SalesforceException(msg,
                        createRestException(request));
                    exception.setStatusCode(responseStatus);
                    callback.onResponse(null, exception);
                } else {
                    // TODO not memory efficient for large response messages,
                    // doesn't seem to be possible in Jetty 7 to directly stream to response parsers
                    final byte[] bytes = request.getResponseContentBytes();
                    callback.onResponse(bytes != null ? new ByteArrayInputStream(bytes) : null, null);
                }

            }

            @Override
            public void onResponseStatus(Buffer version, int status, Buffer reason) throws IOException {
                super.onResponseStatus(version, status, reason);
                // remember status reason
                this.reason = reason.toString(StringUtil.__ISO_8859_1);
            }
        });

        // execute the request
        try {
            httpClient.send(request);
        } catch (IOException e) {
            String msg = "Unexpected Error: " + e.getMessage();
            // send error through callback
            callback.onResponse(null, new SalesforceException(msg, e));
        }

    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public void setInstanceUrl(String instanceUrl) {
        this.instanceUrl = instanceUrl;
    }

    protected abstract void setAccessToken(HttpExchange httpExchange);

    protected abstract SalesforceException createRestException(ContentExchange httpExchange);

}
