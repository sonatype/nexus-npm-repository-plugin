package com.bolyuba.nexus.plugin.npm.transport.internal;

import java.io.IOException;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.nexus.mime.MimeSupport;
import org.sonatype.sisu.goodies.common.ComponentSupport;

import com.bolyuba.nexus.plugin.npm.NpmRepository;
import com.bolyuba.nexus.plugin.npm.transport.Tarball;
import com.bolyuba.nexus.plugin.npm.transport.TarballRequest;
import com.google.common.base.Throwables;

import static com.google.common.base.Preconditions.checkNotNull;

@Singleton
@Named
public class TikaPayloadValidator
    extends ComponentSupport
    implements TarballValidator
{
  /**
   * This is MIME type we expect Tika to detect. This is NOT the MIME type we advertise downstream, which
   * is {@link NpmRepository#TARBALL_MIME_TYPE}.
   */
  private static final String[] EXPECTED_MIME_TYPES = {"application/x-gzip", "application/gzip"};

  private final MimeSupport mimeSupport;

  @Inject
  public TikaPayloadValidator(final MimeSupport mimeSupport) {
    this.mimeSupport = checkNotNull(mimeSupport);
  }

  @Override
  public Result validate(final TarballRequest request, final Tarball tarball) {
    try {
      final List<String> detectedMimeTypes = mimeSupport.detectMimeTypesListFromContent(tarball);
      log.trace("Tike detected content of {} as '{}'", tarball.getOriginUrl(), detectedMimeTypes);
      for (String expectedMimeType : EXPECTED_MIME_TYPES) {
        if (detectedMimeTypes.contains(expectedMimeType)) {
          return Result.VALID;
        }
      }
      return Result.INVALID;
    }
    catch (IOException e) {
      throw Throwables.propagate(e);
    }
  }
}
