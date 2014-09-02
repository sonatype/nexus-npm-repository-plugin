package com.bolyuba.nexus.plugin.npm.transport;

import java.io.File;

import org.sonatype.nexus.proxy.item.FileContentLocator;

import com.bolyuba.nexus.plugin.npm.NpmRepository;

/**
 * Tarball with it's freshly calculated SHA1 hash backed by a temp file, hence, the content is NOT reusable.
 */
public class Tarball
    extends FileContentLocator
{
  private final String sha1sum;

  public Tarball(final File tempFile, final String sha1sum) {
    super(tempFile, NpmRepository.TARBALL_MIME_TYPE, true);
    this.sha1sum = sha1sum;
  }

  public String getSha1sum() { return sha1sum; }
}
