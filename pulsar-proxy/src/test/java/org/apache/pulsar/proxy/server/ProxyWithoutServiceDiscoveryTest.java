/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.proxy.server;

import static org.mockito.Mockito.spy;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.bookkeeper.test.PortManager;
import org.apache.pulsar.broker.authentication.AuthenticationProviderTls;
import org.apache.pulsar.broker.authentication.AuthenticationService;
import org.apache.pulsar.client.admin.PulsarAdmin;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.Message;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.ProducerConsumerBase;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.apache.pulsar.client.impl.auth.AuthenticationTls;
import org.apache.pulsar.common.configuration.PulsarConfigurationLoader;
import org.apache.pulsar.common.policies.data.ClusterData;
import org.apache.pulsar.common.policies.data.TenantInfo;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.testng.collections.Maps;

import com.google.common.collect.Sets;

public class ProxyWithoutServiceDiscoveryTest extends ProducerConsumerBase {
    private static final Logger log = LoggerFactory.getLogger(ProxyWithoutServiceDiscoveryTest.class);

    private final String TLS_TRUST_CERT_FILE_PATH = "./src/test/resources/authentication/tls/cacert.pem";
    private final String TLS_SERVER_CERT_FILE_PATH = "./src/test/resources/authentication/tls/server-cert.pem";
    private final String TLS_SERVER_KEY_FILE_PATH = "./src/test/resources/authentication/tls/server-key.pem";
    private final String TLS_CLIENT_CERT_FILE_PATH = "./src/test/resources/authentication/tls/client-cert.pem";
    private final String TLS_CLIENT_KEY_FILE_PATH = "./src/test/resources/authentication/tls/client-key.pem";

    private ProxyService proxyService;
    private ProxyConfiguration proxyConfig = new ProxyConfiguration();

    @BeforeMethod
    @Override
    protected void setup() throws Exception {

        // enable tls and auth&auth at broker
        conf.setAuthenticationEnabled(true);
        conf.setAuthorizationEnabled(false);

        conf.setBrokerServicePortTls(BROKER_PORT_TLS);
        conf.setWebServicePortTls(BROKER_WEBSERVICE_PORT_TLS);
        conf.setTlsTrustCertsFilePath(TLS_TRUST_CERT_FILE_PATH);
        conf.setTlsCertificateFilePath(TLS_SERVER_CERT_FILE_PATH);
        conf.setTlsKeyFilePath(TLS_SERVER_KEY_FILE_PATH);
        conf.setTlsAllowInsecureConnection(true);

        Set<String> superUserRoles = new HashSet<>();
        superUserRoles.add("superUser");
        conf.setSuperUserRoles(superUserRoles);

        conf.setBrokerClientAuthenticationPlugin(AuthenticationTls.class.getName());
        conf.setBrokerClientAuthenticationParameters(
                "tlsCertFile:" + TLS_CLIENT_CERT_FILE_PATH + "," + "tlsKeyFile:" + TLS_SERVER_KEY_FILE_PATH);

        Set<String> providers = new HashSet<>();
        providers.add(AuthenticationProviderTls.class.getName());
        conf.setAuthenticationProviders(providers);

        conf.setClusterName("without-service-discovery");

        super.init();

        // start proxy service
        proxyConfig.setAuthenticationEnabled(true);
        proxyConfig.setAuthorizationEnabled(false);
        proxyConfig.setBrokerServiceURL("pulsar://localhost:" + BROKER_PORT);
        proxyConfig.setBrokerServiceURLTLS("pulsar://localhost:" + BROKER_PORT_TLS);

        proxyConfig.setServicePort(PortManager.nextFreePort());
        proxyConfig.setServicePortTls(PortManager.nextFreePort());
        proxyConfig.setWebServicePort(PortManager.nextFreePort());
        proxyConfig.setWebServicePortTls(PortManager.nextFreePort());
        proxyConfig.setTlsEnabledWithBroker(true);

        // enable tls and auth&auth at proxy
        proxyConfig.setTlsCertificateFilePath(TLS_SERVER_CERT_FILE_PATH);
        proxyConfig.setTlsKeyFilePath(TLS_SERVER_KEY_FILE_PATH);
        proxyConfig.setTlsTrustCertsFilePath(TLS_TRUST_CERT_FILE_PATH);

        proxyConfig.setBrokerClientAuthenticationPlugin(AuthenticationTls.class.getName());
        proxyConfig.setBrokerClientAuthenticationParameters(
                "tlsCertFile:" + TLS_CLIENT_CERT_FILE_PATH + "," + "tlsKeyFile:" + TLS_CLIENT_KEY_FILE_PATH);
        proxyConfig.setBrokerClientTrustCertsFilePath(TLS_TRUST_CERT_FILE_PATH);

        proxyConfig.setAuthenticationProviders(providers);

        proxyService = Mockito.spy(new ProxyService(proxyConfig,
                                                    new AuthenticationService(
                                                            PulsarConfigurationLoader.convertFrom(proxyConfig))));

        proxyService.start();
    }

    @AfterMethod
    @Override
    protected void cleanup() throws Exception {
        super.internalCleanup();
        proxyService.close();
    }

    /**
     * <pre>
     * It verifies e2e tls + Authentication + Authorization (client -> proxy -> broker>
     *
     * 1. client connects to proxy over tls and pass auth-data
     * 2. proxy authenticate client and retrieve client-role
     *    and send it to broker as originalPrincipal over tls
     * 3. client creates producer/consumer via proxy
     * 4. broker authorize producer/consumer create request using originalPrincipal
     *
     * </pre>
     *
     * @throws Exception
     */
    @Test
    public void testDiscoveryService() throws Exception {
        log.info("-- Starting {} test --", methodName);

        final String proxyServiceUrl = "pulsar://localhost:" + proxyConfig.getServicePortTls().get();
        Map<String, String> authParams = Maps.newHashMap();
        authParams.put("tlsCertFile", TLS_CLIENT_CERT_FILE_PATH);
        authParams.put("tlsKeyFile", TLS_CLIENT_KEY_FILE_PATH);
        Authentication authTls = new AuthenticationTls();
        authTls.configure(authParams);
        // create a client which connects to proxy over tls and pass authData
        PulsarClient proxyClient = createPulsarClient(authTls, proxyServiceUrl);

        admin.clusters().createCluster("without-service-discovery", new ClusterData(brokerUrl.toString()));

        admin.tenants().createTenant("my-property", new TenantInfo(Sets.newHashSet("appid1", "appid2"),
                Sets.newHashSet("without-service-discovery")));
        admin.namespaces().createNamespace("my-property/without-service-discovery/my-ns");

        Consumer<byte[]> consumer = proxyClient.newConsumer()
                .topic("persistent://my-property/without-service-discovery/my-ns/my-topic1")
                .subscriptionName("my-subscriber-name").subscribe();
        Producer<byte[]> producer = proxyClient.newProducer(Schema.BYTES)
                .topic("persistent://my-property/without-service-discovery/my-ns/my-topic1").create();
        final int msgs = 10;
        for (int i = 0; i < msgs; i++) {
            String message = "my-message-" + i;
            producer.send(message.getBytes());
        }

        Message<byte[]> msg = null;
        Set<String> messageSet = Sets.newHashSet();
        int count = 0;
        for (int i = 0; i < 10; i++) {
            msg = consumer.receive(5, TimeUnit.SECONDS);
            String receivedMessage = new String(msg.getData());
            log.debug("Received message: [{}]", receivedMessage);
            String expectedMessage = "my-message-" + i;
            testMessageOrderAndDuplicates(messageSet, receivedMessage, expectedMessage);
            count++;
        }
        // Acknowledge the consumption of all messages at once
        Assert.assertEquals(msgs, count);
        consumer.acknowledgeCumulative(msg);
        consumer.close();
        log.info("-- Exiting {} test --", methodName);
    }

    protected final PulsarClient createPulsarClient(Authentication auth, String lookupUrl) throws Exception {
        admin = spy(PulsarAdmin.builder().serviceHttpUrl(brokerUrlTls.toString()).tlsTrustCertsFilePath(TLS_TRUST_CERT_FILE_PATH)
                .allowTlsInsecureConnection(true).authentication(auth).build());
        return PulsarClient.builder().serviceUrl(lookupUrl).statsInterval(0, TimeUnit.SECONDS)
                .tlsTrustCertsFilePath(TLS_TRUST_CERT_FILE_PATH).allowTlsInsecureConnection(true).authentication(auth)
                .enableTls(true).build();
    }

}
