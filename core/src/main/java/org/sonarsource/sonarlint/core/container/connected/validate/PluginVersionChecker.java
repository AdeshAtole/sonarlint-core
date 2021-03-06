/*
 * SonarLint Core - Implementation
 * Copyright (C) 2016-2021 SonarSource SA
 * mailto:info AT sonarsource DOT com
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
package org.sonarsource.sonarlint.core.container.connected.validate;

import java.io.IOException;
import java.util.Properties;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonarsource.sonarlint.core.client.api.common.Version;

public class PluginVersionChecker {
  public static final String MIN_VERSIONS_FILE = "/plugins_min_versions.txt";

  private final Properties minimalPluginVersions;

  public PluginVersionChecker() {
    this.minimalPluginVersions = new Properties();
    try {
      minimalPluginVersions.load(this.getClass().getResourceAsStream(MIN_VERSIONS_FILE));
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load minimum plugin versions", e);
    }

  }

  @CheckForNull
  public String getMinimumVersion(String key) {
    return minimalPluginVersions.getProperty(key);
  }

  public boolean isVersionSupported(String key, @Nullable String version) {
    if (version != null) {
      Version v = Version.create(version);
      return isVersionSupported(key, v);
    }
    return true;
  }

  public boolean isVersionSupported(String key, @Nullable Version version) {
    String minVersion = getMinimumVersion(key);
    if (version != null && minVersion != null) {
      Version minimalVersion = Version.create(minVersion);
      return version.compareTo(minimalVersion) >= 0;
    }
    return true;
  }
}
