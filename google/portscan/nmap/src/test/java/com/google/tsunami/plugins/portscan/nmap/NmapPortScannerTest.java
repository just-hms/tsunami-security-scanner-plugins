/*
 * Copyright 2020 Google LLC
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
package com.google.tsunami.plugins.portscan.nmap;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.extensions.proto.ProtoTruth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.tsunami.common.command.CommandExecutionThreadPool;
import com.google.tsunami.common.data.NetworkEndpointUtils;
import com.google.tsunami.plugins.portscan.nmap.client.NmapClient;
import com.google.tsunami.plugins.portscan.nmap.client.parser.XMLParser;
import com.google.tsunami.plugins.portscan.nmap.client.testing.SpyNmapClientModule;
import com.google.tsunami.plugins.portscan.nmap.option.NmapPortScannerCliOptions;
import com.google.tsunami.proto.NetworkEndpoint;
import com.google.tsunami.proto.NetworkService;
import com.google.tsunami.proto.PortScanningReport;
import com.google.tsunami.proto.ScanTarget;
import com.google.tsunami.proto.TargetInfo;
import com.google.tsunami.proto.TransportProtocol;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import javax.inject.Inject;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Spy;
import org.xml.sax.SAXException;

/** Tests for {@link NmapPortScanner}. */
@RunWith(JUnit4.class)
public class NmapPortScannerTest {
  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  @Inject @Spy private NmapClient nmapClient;
  @Inject private NmapPortScanner portScanner;

  private final NmapPortScannerConfigs configs = new NmapPortScannerConfigs();
  private final NmapPortScannerCliOptions cliOptions = new NmapPortScannerCliOptions();

  @Before
  public void setUp() throws IOException {
    File fakeNmapFile = tempFolder.newFile("fakeNmap");
    File fakeOutputFile = tempFolder.newFile("fakeOutput");
    Guice.createInjector(
            new SpyNmapClientModule(fakeNmapFile.getAbsolutePath(), fakeOutputFile),
            // TODO(b/145315535): remove this once CommandExecutor library is refactored.
            new AbstractModule() {
              @Override
              protected void configure() {
                bind(NmapPortScannerConfigs.class).toInstance(configs);
                bind(NmapPortScannerCliOptions.class).toInstance(cliOptions);
                bind(Executor.class)
                    .annotatedWith(CommandExecutionThreadPool.class)
                    .toInstance(MoreExecutors.directExecutor());
              }
            })
        .injectMembers(this);
  }

  @Test
  public void run_whenNmapRunHasOpenPorts_returnsMatchingService()
      throws InterruptedException, ExecutionException, IOException, ParserConfigurationException,
          SAXException {
    doReturn(XMLParser.parse(getClass().getResourceAsStream("testdata/localhostSsh.xml")))
        .when(nmapClient)
        .run(any());
    NetworkEndpoint networkEndpoint = NetworkEndpointUtils.forIp("127.0.0.1");
    assertThat(
            portScanner.scan(ScanTarget.newBuilder().setNetworkEndpoint(networkEndpoint).build()))
        .isEqualTo(
            PortScanningReport.newBuilder()
                .setTargetInfo(TargetInfo.newBuilder().addNetworkEndpoints(networkEndpoint))
                .addNetworkServices(
                    NetworkService.newBuilder()
                        .setNetworkEndpoint(NetworkEndpointUtils.forIpAndPort("127.0.0.1", 22))
                        .setTransportProtocol(TransportProtocol.TCP)
                        .setServiceName("ssh")
                        .addBanner("SSH-2.0-OpenSSH_7.9 MDI-2.0"))
                .build());
  }

  @Test
  public void run_whenNmapRunReportsClosedPort_returnsEmptyServices()
      throws InterruptedException, ExecutionException, IOException, ParserConfigurationException,
          SAXException {
    doReturn(XMLParser.parse(getClass().getResourceAsStream("testdata/closedTelnet.xml")))
        .when(nmapClient)
        .run(any());
    NetworkEndpoint networkEndpoint = NetworkEndpointUtils.forIp("127.0.0.1");

    assertThat(
            portScanner.scan(ScanTarget.newBuilder().setNetworkEndpoint(networkEndpoint).build()))
        .isEqualTo(
            PortScanningReport.newBuilder()
                .setTargetInfo(TargetInfo.newBuilder().addNetworkEndpoints(networkEndpoint))
                .build());
  }

  @Test
  public void run_configHasPortTargets_scansAllTargets()
      throws InterruptedException, ExecutionException, IOException, ParserConfigurationException,
          SAXException {
    configs.portTargets = "80,8080,15000-16000";
    doReturn(XMLParser.parse(getClass().getResourceAsStream("testdata/localhostSsh.xml")))
        .when(nmapClient)
        .run(any());

    portScanner.scan(
        ScanTarget.newBuilder()
            .setNetworkEndpoint(NetworkEndpointUtils.forIp("127.0.0.1"))
            .build());

    ArgumentCaptor<Integer> portTargetCaptor = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> rangeStartCaptor = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> rangeEndCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(nmapClient, atLeastOnce()).onPort(portTargetCaptor.capture());
    verify(nmapClient, atLeastOnce())
        .onPortRange(rangeStartCaptor.capture(), rangeEndCaptor.capture());
    assertThat(portTargetCaptor.getAllValues()).containsExactly(80, 8080);
    assertThat(rangeStartCaptor.getAllValues()).containsExactly(15000);
    assertThat(rangeEndCaptor.getAllValues()).containsExactly(16000);
  }

  @Test
  public void run_configHasInvalidPorts_throwsException()
      throws InterruptedException, ExecutionException, IOException, ParserConfigurationException,
          SAXException {
    configs.portTargets = "80,8080,abcd";
    doReturn(XMLParser.parse(getClass().getResourceAsStream("testdata/localhostSsh.xml")))
        .when(nmapClient)
        .run(any());

    assertThrows(
        NumberFormatException.class,
        () ->
            portScanner.scan(
                ScanTarget.newBuilder()
                    .setNetworkEndpoint(NetworkEndpointUtils.forIp("127.0.0.1"))
                    .build()));
  }

  @Test
  public void run_cliArgsHavePorts_overwriteConfigPorts()
      throws InterruptedException, ExecutionException, IOException, ParserConfigurationException,
          SAXException {
    configs.portTargets = "80,8080,15000-16000";
    cliOptions.portRangesTarget = "80,10000-11000";
    doReturn(XMLParser.parse(getClass().getResourceAsStream("testdata/localhostSsh.xml")))
        .when(nmapClient)
        .run(any());

    portScanner.scan(
        ScanTarget.newBuilder()
            .setNetworkEndpoint(NetworkEndpointUtils.forIp("127.0.0.1"))
            .build());

    ArgumentCaptor<Integer> portTargetCaptor = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> rangeStartCaptor = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> rangeEndCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(nmapClient, atLeastOnce()).onPort(portTargetCaptor.capture());
    verify(nmapClient, atLeastOnce())
        .onPortRange(rangeStartCaptor.capture(), rangeEndCaptor.capture());
    assertThat(portTargetCaptor.getAllValues()).containsExactly(80);
    assertThat(rangeStartCaptor.getAllValues()).containsExactly(10000);
    assertThat(rangeEndCaptor.getAllValues()).containsExactly(11000);
  }

  @Test
  public void run_cliArgsHaveRootPaths_createSeparateNetworkServiceForEachPath()
      throws InterruptedException, ExecutionException, IOException, ParserConfigurationException,
          SAXException {
    configs.portTargets = "80";
    cliOptions.rootPathsTarget = Arrays.asList("/root1", "/root2");
    doReturn(XMLParser.parse(getClass().getResourceAsStream("testdata/localhostHttp.xml")))
        .when(nmapClient)
        .run(any());

    portScanner.scan(
        ScanTarget.newBuilder()
            .setNetworkEndpoint(NetworkEndpointUtils.forIp("127.0.0.1"))
            .build());
    NetworkEndpoint networkEndpoint = NetworkEndpointUtils.forIp("127.0.0.1");
    PortScanningReport result =
        portScanner.scan(ScanTarget.newBuilder().setNetworkEndpoint(networkEndpoint).build());
    assertThat(result.getNetworkServicesCount()).isEqualTo(2);
    assertThat(
            result
                .getNetworkServices(0)
                .getServiceContext()
                .getWebServiceContext()
                .getApplicationRoot())
        .isEqualTo("/root1");
    assertThat(
            result
                .getNetworkServices(1)
                .getServiceContext()
                .getWebServiceContext()
                .getApplicationRoot())
        .isEqualTo("/root2");
  }

  @Test
  public void run_cliArgsHaveRootPaths_dontIncludePathIfNotWebService()
      throws InterruptedException, ExecutionException, IOException, ParserConfigurationException,
          SAXException {
    configs.portTargets = "22";
    cliOptions.rootPathsTarget = Arrays.asList("/root1", "/root2");
    doReturn(XMLParser.parse(getClass().getResourceAsStream("testdata/localhostSsh.xml")))
        .when(nmapClient)
        .run(any());

    portScanner.scan(
        ScanTarget.newBuilder()
            .setNetworkEndpoint(NetworkEndpointUtils.forIp("127.0.0.1"))
            .build());
    NetworkEndpoint networkEndpoint = NetworkEndpointUtils.forIp("127.0.0.1");
    PortScanningReport result =
        portScanner.scan(ScanTarget.newBuilder().setNetworkEndpoint(networkEndpoint).build());
    // There should only be a single service identified even though two root paths were specified.
    assertThat(result.getNetworkServicesCount()).isEqualTo(1);
    assertThat(
            result
                .getNetworkServices(0)
                .getServiceContext()
                .getWebServiceContext()
                .getApplicationRoot())
        .isEmpty();
  }
}