package com.bolyuba.nexus.plugin.npm.transport.internal;

import com.bolyuba.nexus.plugin.npm.transport.Tarball;
import com.bolyuba.nexus.plugin.npm.transport.TarballRequest;

public interface TarballValidator
{
  public enum Result
  {
    INVALID, NEUTRAL, VALID
  }

  /**
   * Validates tarball and cleanly returns if all found clean. Otherwise, preferred way to signal invalid content is to
   * throw {@link IllegalArgumentException}. Never returns {@code null}.
   */
  Result validate(TarballRequest request, Tarball tarball);
}
