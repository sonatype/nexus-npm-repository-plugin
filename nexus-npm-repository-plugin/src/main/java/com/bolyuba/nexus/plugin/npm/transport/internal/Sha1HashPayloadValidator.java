package com.bolyuba.nexus.plugin.npm.transport.internal;

import javax.inject.Named;
import javax.inject.Singleton;

import org.sonatype.sisu.goodies.common.ComponentSupport;

import com.bolyuba.nexus.plugin.npm.transport.Tarball;
import com.bolyuba.nexus.plugin.npm.transport.TarballRequest;

@Singleton
@Named
public class Sha1HashPayloadValidator
    extends ComponentSupport
    implements TarballValidator
{
  @Override
  public Result validate(final TarballRequest request, final Tarball tarball) {
    // checksum validation: if present in metadata (usually is) as repo itself has no policy settings
    final String expectedShasum = request.getPackageVersion().getDistShasum();
    if (expectedShasum != null && !expectedShasum.equals(tarball.getSha1sum())) {
      return Result.INVALID;
    }
    else if (expectedShasum == null) {
      return Result.NEUTRAL;
    }
    else {
      return Result.VALID;
    }
  }
}
