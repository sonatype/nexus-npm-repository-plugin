package com.bolyuba.nexus.plugin.npm.transport.internal;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.apachehttpclient.Hc4Provider;
import org.sonatype.nexus.util.DigesterUtils;
import org.sonatype.sisu.goodies.common.ComponentSupport;

import com.bolyuba.nexus.plugin.npm.NpmRepository;
import com.bolyuba.nexus.plugin.npm.proxy.NpmProxyRepository;
import com.bolyuba.nexus.plugin.npm.transport.Tarball;
import com.google.common.base.Throwables;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Apache HttpClient backed tarball transport implementation.
 */
@Singleton
@Named
public class HttpTarballTransport
    extends ComponentSupport
{
  private static final Logger outboundRequestLog = LoggerFactory.getLogger("remote.storage.outbound");

  private final Hc4Provider hc4Provider;

  @Inject
  public HttpTarballTransport(final Hc4Provider hc4Provider) {
    this.hc4Provider = checkNotNull(hc4Provider);
  }

  public Tarball getTarballForVersion(final NpmProxyRepository npmProxyRepository, final File target,
                                      final String tarballUri)
      throws IOException
  {
    final HttpClient httpClient = hc4Provider.createHttpClient(npmProxyRepository.getRemoteStorageContext());
    final HttpGet get = new HttpGet(tarballUri);
    // TODO: should be DEBUG
    outboundRequestLog.info("{} - NPMTarball GET {}", npmProxyRepository.getId(), get.getURI());
    final HttpClientContext context = new HttpClientContext();
    context.setAttribute(Hc4Provider.HTTP_CTX_KEY_REPOSITORY, npmProxyRepository);
    get.addHeader("Accept", NpmRepository.TARBALL_MIME_TYPE);
    final HttpResponse httpResponse = httpClient.execute(get, context);
    try {
      // TODO: should be DEBUG
      outboundRequestLog.info("{} - NPMTarball GET {} - {}", npmProxyRepository.getId(), get.getURI(),
          httpResponse.getStatusLine());
      if (httpResponse.getStatusLine().getStatusCode() == HttpStatus.SC_OK && httpResponse.getEntity() != null) {
        final MessageDigest md = MessageDigest.getInstance("SHA1");
        try (final BufferedOutputStream bos = new BufferedOutputStream(
            new DigestOutputStream(new FileOutputStream(target), md))) {
          httpResponse.getEntity().writeTo(bos);
          bos.flush();
        }
        return new Tarball(target, tarballUri, DigesterUtils.getDigestAsString(md.digest()));
      }
      else {
        // TODO: might be redundant now, but once those logs above go to DEBUG will not
        log.warn("{} - NPMTarball GET {}: unexpected response: {}", npmProxyRepository.getId(), get.getURI(),
            httpResponse.getStatusLine());
      }
      return null;
    }
    catch (NoSuchAlgorithmException e) {
      throw Throwables.propagate(e);
    }
    finally {
      EntityUtils.consumeQuietly(httpResponse.getEntity());
    }
  }
}
