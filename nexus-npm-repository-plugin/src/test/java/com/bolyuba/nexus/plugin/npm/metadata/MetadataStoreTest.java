package com.bolyuba.nexus.plugin.npm.metadata;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;

import org.sonatype.nexus.configuration.application.ApplicationDirectories;
import org.sonatype.nexus.proxy.item.ContentLocator;
import org.sonatype.nexus.proxy.item.PreparedContentLocator;
import org.sonatype.nexus.proxy.item.StringContentLocator;
import org.sonatype.nexus.proxy.registry.ContentClass;
import org.sonatype.nexus.proxy.repository.ProxyRepository;
import org.sonatype.nexus.proxy.storage.remote.RemoteStorageContext;
import org.sonatype.nexus.proxy.storage.remote.httpclient.HttpClientManager;
import org.sonatype.sisu.litmus.testsupport.TestSupport;

import com.bolyuba.nexus.plugin.npm.NpmRepository;
import com.bolyuba.nexus.plugin.npm.NpmUtility;
import com.bolyuba.nexus.plugin.npm.hosted.DefaultNpmHostedRepository;
import com.bolyuba.nexus.plugin.npm.hosted.NpmHostedRepository;
import com.bolyuba.nexus.plugin.npm.hosted.NpmHostedRepositoryConfigurator;
import com.bolyuba.nexus.plugin.npm.metadata.internal.MetadataConsumer;
import com.bolyuba.nexus.plugin.npm.metadata.internal.MetadataParser;
import com.bolyuba.nexus.plugin.npm.metadata.internal.MetadataServiceFactoryImpl;
import com.bolyuba.nexus.plugin.npm.metadata.internal.orient.OrientMetadataStore;
import com.bolyuba.nexus.plugin.npm.proxy.DefaultNpmProxyRepository;
import com.bolyuba.nexus.plugin.npm.proxy.NpmProxyRepository;
import com.bolyuba.nexus.plugin.npm.proxy.NpmProxyRepositoryConfigurator;
import com.google.common.base.Charsets;
import com.google.common.io.ByteSource;
import com.google.common.io.Files;
import org.apache.http.impl.client.HttpClients;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.skyscreamer.jsonassert.JSONAssert;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MetadataStoreTest
    extends TestSupport
{
  private File tmpDir;

  private NpmHostedRepository npmHostedRepository;

  private NpmProxyRepository npmProxyRepository;

  @Mock
  private ApplicationDirectories applicationDirectories;

  @Mock
  private HttpClientManager httpClientManager;

  private OrientMetadataStore metadataStore;

  private MetadataServiceFactory metadataService;

  private HostedMetadataService hostedMetadataService;

  private ProxyMetadataService proxyMetadataService;

  @Before
  public void setup() throws Exception {
    tmpDir = util.createTempDir();

    when(applicationDirectories.getWorkDirectory(anyString())).thenReturn(tmpDir);
    when(applicationDirectories.getTemporaryDirectory()).thenReturn(tmpDir);
    when(httpClientManager.create(any(ProxyRepository.class), any(RemoteStorageContext.class))).thenReturn(
        HttpClients.createDefault());

    // not using mock as it would OOM when it tracks invocations, as we work with large files here
    npmHostedRepository = new DefaultNpmHostedRepository(mock(ContentClass.class), mock(
        NpmHostedRepositoryConfigurator.class), mock(NpmUtility.class))
    {
      @Override
      public String getId() {
        return "hosted";
      }
    };

    npmProxyRepository = new DefaultNpmProxyRepository(mock(ContentClass.class), mock(
        NpmProxyRepositoryConfigurator.class), mock(NpmUtility.class))
    {
      @Override
      public String getId() {
        return "proxy";
      }

      @Override
      public boolean isItemAgingActive() { return true; }

      @Override
      public int getItemMaxAge() { return 10; }

      @Override
      public String getRemoteUrl() { return "http://registry.npmjs.org/"; }
    };

    metadataStore = new OrientMetadataStore(applicationDirectories);
    metadataService = new MetadataServiceFactoryImpl(applicationDirectories, metadataStore, httpClientManager);
    hostedMetadataService = metadataService.createHostedMetadataService(npmHostedRepository);
    proxyMetadataService = metadataService.createProxyMetadataService(npmProxyRepository);

    metadataStore.start();
  }

  @After
  public void teardown() throws Exception {
    metadataStore.stop();
  }

  /**
   * Simple smoke test that pushes _real_ NPM registry root data into the store and then performs some queries
   * against it. This is huge, as we operate on a 40MB JSON file, database will have around 90k entries.
   */
  @Test
  public void registryRootRoundtrip() throws Exception {
    final ContentLocator input = new PreparedContentLocator(
        new GZIPInputStream(new FileInputStream(util.resolveFile("src/test/npm/ROOT_all.json.gz"))),
        NpmRepository.JSON_MIME_TYPE, -1);
    // this is "illegal" case using internal stuff, but is for testing only
    final MetadataParser parser = new MetadataParser(applicationDirectories.getTemporaryDirectory(), npmProxyRepository);
    final MetadataConsumer consumer = new MetadataConsumer(
        npmProxyRepository,
        parser, metadataStore);
    consumer.consumeRegistryRoot(input);

    log("Splice done");
    // we pushed all into DB, now query
    log(metadataStore.listPackageNames(npmProxyRepository).size());

    final PackageRoot commonjs = metadataStore.getPackageByName(npmProxyRepository, "commonjs");
    log(commonjs.getName() + " || " + commonjs.getVersions().keySet() + "unpublished=" + commonjs.isUnpublished() +
        " incomplete=" + commonjs.isIncomplete());

    final ContentLocator output = proxyMetadataService.produceRegistryRoot();
    try (InputStream is = output.getContent()) {
      java.nio.file.Files.copy(is, new File(tmpDir, "root.json").toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
  }

  @Test
  public void proxyPackageRootRoundtrip() throws Exception {
    // this call will get it from remote, store, and return it as raw stream
    final StringContentLocator contentLocator = (StringContentLocator) proxyMetadataService
        .producePackageVersion("commonjs", "0.0.1");
    JSONObject proxiedV001 = new JSONObject(
        ByteSource.wrap(contentLocator.getByteArray()).asCharSource(Charsets.UTF_8).read());

    // get the one from file
    final File jsonFile = util.resolveFile("src/test/npm/ROOT_commonjs.json");
    JSONObject onDisk = new JSONObject(Files.toString(jsonFile, Charsets.UTF_8));
    JSONObject versions = onDisk.getJSONObject("versions");
    JSONObject diskV001 = versions.getJSONObject("0.0.1");

    JSONAssert.assertEquals(diskV001, proxiedV001, false);
  }

  /**
   * Simple smoke test that pushes _real_ NPM registry package root and then queries it, like in a hosted repository.
   */
  @Test
  public void hostedPackageRootRoundtrip() throws Exception {
    final File jsonFile = util.resolveFile("src/test/npm/ROOT_commonjs.json");
    final ContentLocator input = new PreparedContentLocator(
        new FileInputStream(jsonFile),
        NpmRepository.JSON_MIME_TYPE, -1);
    hostedMetadataService.consumePackageRoot(input);

    assertThat(metadataStore.listPackageNames(npmHostedRepository), hasSize(1));

    final PackageRoot commonjs = metadataStore.getPackageByName(npmHostedRepository, "commonjs");
    assertThat(commonjs.getName(), equalTo("commonjs"));
    assertThat(commonjs.isUnpublished(), is(false));
    assertThat(commonjs.isIncomplete(), is(false));

    final PackageVersion commonjs_0_0_1 = commonjs.getVersions().get("0.0.1");
    assertThat(commonjs_0_0_1, notNullValue());
    assertThat(commonjs_0_0_1.getName(), equalTo("commonjs"));
    assertThat(commonjs_0_0_1.getVersion(), equalTo("0.0.1"));
    assertThat(commonjs_0_0_1.isIncomplete(), is(false));

    JSONObject onDisk = new JSONObject(Files.toString(jsonFile, Charsets.UTF_8));
    onDisk.remove("_attachments");
    final StringContentLocator contentLocator = (StringContentLocator) hostedMetadataService
        .producePackageRoot("commonjs");
    JSONObject onStore = new JSONObject(
        ByteSource.wrap(contentLocator.getByteArray()).asCharSource(Charsets.UTF_8).read());

    JSONAssert.assertEquals(onDisk, onStore, false);
  }
}
