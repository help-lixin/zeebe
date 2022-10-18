/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.it.network;

import io.atomix.utils.net.Address;
import io.camunda.zeebe.client.api.response.Topology;
import io.camunda.zeebe.qa.util.testcontainers.ContainerLogsDumper;
import io.camunda.zeebe.qa.util.testcontainers.ZeebeTestContainerDefaults;
import io.camunda.zeebe.test.util.asserts.SslAssert;
import io.camunda.zeebe.test.util.asserts.TopologyAssert;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.zeebe.containers.ZeebeNode;
import io.zeebe.containers.cluster.ZeebeCluster;
import java.security.cert.CertificateException;
import java.util.concurrent.TimeUnit;
import org.agrona.CloseHelper;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

@Testcontainers
final class SecureClusteredMessagingIT {
  private static final Logger LOGGER = LoggerFactory.getLogger(SecureClusteredMessagingIT.class);

  private final Network network = Network.newNetwork();
  private final SelfSignedCertificate certificate = newCertificate();

  @Container
  private final ZeebeCluster cluster =
      ZeebeCluster.builder()
          .withNetwork(network)
          .withGatewaysCount(1)
          .withBrokersCount(2)
          .withReplicationFactor(2)
          .withEmbeddedGateway(false)
          .withImage(ZeebeTestContainerDefaults.defaultTestImage())
          .withNodeConfig(this::configureNode)
          .build();

  @SuppressWarnings("unused")
  @RegisterExtension
  final ContainerLogsDumper logsWatcher = new ContainerLogsDumper(cluster::getNodes, LOGGER);

  @BeforeEach
  void beforeEach() {
    Configurator.setLevel("io.netty", Level.TRACE);
  }

  @AfterEach
  void afterEach() {
    CloseHelper.quietCloseAll(network);
    Configurator.setLevel("io.netty", Level.INFO);
  }

  @Test
  void shouldFormAClusterWithTls() {
    // given - a cluster with 2 standalone brokers, and 1 standalone gateway

    // when - note the client is using plaintext since we only care about inter-cluster TLS
    final Topology topology;
    try (final var client = cluster.newClientBuilder().usePlaintext().build()) {
      topology = client.newTopologyRequest().send().join(15, TimeUnit.SECONDS);
    }

    // then - ensure the cluster is formed correctly and all inter-cluster communication endpoints
    // are secured using the expected certificate
    TopologyAssert.assertThat(topology).hasBrokersCount(2).isComplete(2, 1, 2);
    cluster
        .getBrokers()
        .forEach(
            (id, broker) -> {
              assertAddressIsSecured(id, broker.getExternalCommandAddress());
              assertAddressIsSecured(id, broker.getExternalClusterAddress());
            });
    cluster
        .getGateways()
        .forEach((id, gateway) -> assertAddressIsSecured(id, gateway.getExternalClusterAddress()));
    LOGGER.debug("Test finished, closing all Docker VMs");
  }

  private void configureNode(final ZeebeNode<?> node) {
    final var certChainPath = "/tmp/certChain.crt";
    final var privateKeyPath = "/tmp/private.key";

    // configure both the broker and gateway; it doesn't really matter if one sees the environment
    // variables of the other
    node.withEnv("ZEEBE_BROKER_NETWORK_SECURITY_ENABLED", "true")
        .withEnv("ZEEBE_BROKER_NETWORK_SECURITY_CERTIFICATECHAINPATH", certChainPath)
        .withEnv("ZEEBE_BROKER_NETWORK_SECURITY_PRIVATEKEYPATH", privateKeyPath)
        .withEnv("ZEEBE_GATEWAY_CLUSTER_SECURITY_ENABLED", "true")
        .withEnv("ZEEBE_GATEWAY_CLUSTER_SECURITY_CERTIFICATECHAINPATH", certChainPath)
        .withEnv("ZEEBE_GATEWAY_CLUSTER_SECURITY_PRIVATEKEYPATH", privateKeyPath)
        .withCopyFileToContainer(
            MountableFile.forHostPath(certificate.certificate().toPath()), certChainPath)
        .withCopyFileToContainer(
            MountableFile.forHostPath(certificate.privateKey().toPath()), privateKeyPath);
  }

  private void assertAddressIsSecured(final Object nodeId, final String address) {
    final var socketAddress = Address.from(address).socketAddress();
    LOGGER.debug("Checking if node {} is secured correctly at {}", nodeId, address);
    SslAssert.assertThat(socketAddress)
        .as("node %s is not secured correctly", nodeId)
        .isSecuredBy(certificate);
  }

  private SelfSignedCertificate newCertificate() {
    try {
      return new SelfSignedCertificate();
    } catch (final CertificateException e) {
      throw new IllegalStateException("Failed to create self-signed certificate", e);
    }
  }
}
