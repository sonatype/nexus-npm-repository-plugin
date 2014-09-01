package com.bolyuba.nexus.plugin.npm.transport.internal;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.apachehttpclient.Hc4Provider;
import org.sonatype.nexus.configuration.application.ApplicationDirectories;
import org.sonatype.nexus.proxy.item.StorageFileItem;
import org.sonatype.nexus.util.DigesterUtils;
import org.sonatype.sisu.goodies.common.ComponentSupport;

import com.bolyuba.nexus.plugin.npm.NpmRepository;
import com.bolyuba.nexus.plugin.npm.metadata.PackageVersion;
import com.bolyuba.nexus.plugin.npm.proxy.NpmProxyRepository;
import com.bolyuba.nexus.plugin.npm.transport.Tarball;
import com.bolyuba.nexus.plugin.npm.transport.TarballSource;
import com.google.common.base.Throwables;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Apache HttpClient backed {@link TarballSource} implementation.
 */
@Singleton
@Named
public class HttpTarballSource
    extends ComponentSupport
    implements TarballSource
{
  private static final Logger outboundRequestLog = LoggerFactory.getLogger("remote.storage.outbound");

  private final ApplicationDirectories applicationDirectories;

  private final Hc4Provider hc4Provider;

  @Inject
  public HttpTarballSource(final ApplicationDirectories applicationDirectories, final Hc4Provider hc4Provider) {
    this.applicationDirectories = checkNotNull(applicationDirectories);
    this.hc4Provider = checkNotNull(hc4Provider);
  }

  @Override
  public Tarball get(final NpmProxyRepository npmProxyRepository, final PackageVersion packageVersion)
      throws IOException
  {
    checkArgument(!packageVersion.isIncomplete(), "Incomplete package version cannot be sourced: %s: %s:%s",
        npmProxyRepository.getId(), packageVersion.getName(), packageVersion.getVersion());
    return getTarballFromUrl(npmProxyRepository, packageVersion);
  }

  private Tarball getTarballFromUrl(final NpmProxyRepository npmProxyRepository, final PackageVersion packageVersion)
      throws IOException
  {
    final HttpClient httpClient = hc4Provider.createHttpClient(npmProxyRepository.getRemoteStorageContext());
    final HttpGet get = new HttpGet(packageVersion.getDistTarball());
    // TODO: should be DEBUG
    outboundRequestLog.info("{} - NPMTarball GET {}", npmProxyRepository.getId(), get.getURI());
    get.addHeader("accept", NpmRepository.TARBALL_MIME_TYPE);
    final HttpResponse httpResponse = httpClient.execute(get);
    try {
      // TODO: should be DEBUG
      outboundRequestLog.info("{} - NPMTarball GET {} - {}", npmProxyRepository.getId(), get.getURI(),
          httpResponse.getStatusLine());
      if (httpResponse.getStatusLine().getStatusCode() == HttpStatus.SC_OK) {
        final File tempFile = File
            .createTempFile(npmProxyRepository.getId() + "-tarball", "tgz",
                applicationDirectories.getTemporaryDirectory());
        final MessageDigest md = MessageDigest.getInstance("SHA1");
        try (final BufferedOutputStream bos = new BufferedOutputStream(
            new DigestOutputStream(new FileOutputStream(tempFile), md))) {
          httpResponse.getEntity().writeTo(bos);
          bos.flush();
        }
        // checksum validation: if present in metadata (usually is)
        final String downloadedShasum = DigesterUtils.getDigestAsString(md.digest());
        final String expectedShasum = packageVersion.getDistShasum();
        if (expectedShasum != null && !expectedShasum.equals(downloadedShasum)) {
          log.warn("Download corrupted, expected SHA1: {} calculated SHA1: {}", expectedShasum, downloadedShasum);
          Files.deleteIfExists(tempFile.toPath());
          return null;
        }
        // TODO: content validation
        // TODO: eventing?
        return new Tarball(tempFile, packageVersion, downloadedShasum);
      } else {
        // TODO: might be redundant now, but once those logs above go to DEBUG will not
        log.warn("{} - NPMTarball GET {}: unexpected response: {}", npmProxyRepository.getId(), get.getURI(), httpResponse.getStatusLine());
      }
    }
    catch (NoSuchAlgorithmException e) {
      throw Throwables.propagate(e);
    }
    finally {
      EntityUtils.consumeQuietly(httpResponse.getEntity());
    }
    return null;
  }
}
