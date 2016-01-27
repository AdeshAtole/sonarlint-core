/*
 * SonarLint Core Library
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarsource.sonarlint.core.container.analysis.filesystem;

import com.google.common.annotations.VisibleForTesting;
import java.io.File;
import java.nio.charset.Charset;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.BatchSide;

@BatchSide
public class FileSystemLogger {

  private final DefaultModuleFileSystem fs;

  public FileSystemLogger(DefaultModuleFileSystem fs) {
    this.fs = fs;
  }

  public void log() {
    doLog(LoggerFactory.getLogger(getClass()));
  }

  @VisibleForTesting
  void doLog(Logger logger) {
    logDir(logger, "Base dir: ", fs.baseDir());
    logDir(logger, "Working dir: ", fs.workDir());
    logEncoding(logger, fs.encoding());
  }

  private void logEncoding(Logger logger, Charset charset) {
    if (!fs.isDefaultJvmEncoding()) {
      logger.info("Source encoding: " + charset.displayName() + ", default locale: " + Locale.getDefault());
    } else {
      logger.warn("Source encoding is platform dependent (" + charset.displayName() + "), default locale: " + Locale.getDefault());
    }
  }

  private void logDir(Logger logger, String label, File dir) {
    if (dir != null) {
      logger.info(label + dir.getAbsolutePath());
    }
  }
}
