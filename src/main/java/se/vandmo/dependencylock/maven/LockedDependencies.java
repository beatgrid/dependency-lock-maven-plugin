package se.vandmo.dependencylock.maven;

import static java.lang.String.format;
import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.maven.plugin.logging.Log;

public final class LockedDependencies {

  public final Artifacts lockedDependencies;
  private final Log log;

  private LockedDependencies(Artifacts lockedDependencies, Log log) {
    this.lockedDependencies = lockedDependencies;
    this.log = log;
  }

  public static LockedDependencies from(Artifacts artifacts, Log log) {
    return new LockedDependencies(requireNonNull(artifacts), log);
  }

  public Diff compareWith(Artifacts artifacts, String projectVersion, Filters filters) {
    LockFileExpectationsDiff expectationsDiff =
        new LockFileExpectationsDiff(artifacts, projectVersion, filters);
    List<String> unexpected = findUnexpected(artifacts, filters);
    return new Diff(expectationsDiff, unexpected);
  }

  private final class LockFileExpectationsDiff {
    private List<String> missing = new ArrayList<>();
    private List<String> different = new ArrayList<>();

    private LockFileExpectationsDiff(Artifacts artifacts, String projectVersion, Filters filters) {
      for (Artifact lockedDependency : lockedDependencies) {
        Optional<Artifact> possiblyOtherArtifact = artifacts.by(lockedDependency.identifier);
        if (!possiblyOtherArtifact.isPresent()) {
          if (filters.allowMissing(lockedDependency)) {
            log.info(format(ROOT, "Ignoring missing %s", lockedDependency));
          } else {
            missing.add(lockedDependency.identifier.toString());
          }
        } else {
          Artifact actualArtifact = possiblyOtherArtifact.get();
          DependencySetConfiguration.Version versionConfiguration =
              filters.versionConfiguration(lockedDependency);
          switch (versionConfiguration) {
            case check:
              if (!lockedDependency.equals(actualArtifact)) {
                different.add(
                    format(ROOT, "Expected %s but found %s", lockedDependency, actualArtifact));
              }
              break;
            case ignore:
              log.info(format(ROOT, "Ignoring version for %s", lockedDependency));
              if (!lockedDependency.equals_ignoreVersion(actualArtifact)) {
                different.add(
                    format(
                        ROOT,
                        "Expected %s with any version but found %s",
                        lockedDependency,
                        actualArtifact));
              }
              break;
            case useProjectVersion:
              log.info(format(ROOT, "Using project version for %s", lockedDependency));
              if (!lockedDependency.withVersion(projectVersion).equals(actualArtifact)) {
                different.add(
                    format(
                        ROOT,
                        "Expected %s with project version but found %s",
                        lockedDependency,
                        actualArtifact));
              }
              break;
            default:
              throw new RuntimeException("Unsupported enum value");
          }
        }
      }
    }
  }

  private List<String> findUnexpected(Artifacts artifacts, Filters filters) {
    List<String> unexpected = new ArrayList<>();
    for (Artifact artifact : artifacts.artifacts) {
      if (!by(artifact.identifier).isPresent()) {
        if (filters.allowSuperfluous(artifact)) {
          log.info(format(ROOT, "Ignoring extraneous %s", artifact));
        } else {
          unexpected.add(artifact.toString());
        }
      }
    }
    return unexpected;
  }

  public Optional<Artifact> by(ArtifactIdentifier identifier) {
    for (Artifact lockedDependency : lockedDependencies) {
      if (lockedDependency.identifier.equals(identifier)) {
        return Optional.of(lockedDependency);
      }
    }
    return Optional.empty();
  }

  public static final class Diff {
    private final List<String> missing;
    private final List<String> different;
    private final List<String> added;

    private Diff(LockFileExpectationsDiff lockFileExpectationsDiff, List<String> added) {
      this.missing = lockFileExpectationsDiff.missing;
      this.different = lockFileExpectationsDiff.different;
      this.added = added;
    }

    public boolean equals() {
      return missing.isEmpty() && different.isEmpty() && added.isEmpty();
    }

    public void logTo(Log log) {
      if (!missing.isEmpty()) {
        log.error("Missing dependencies:");
        missing.forEach(line -> log.error("  " + line));
      }
      if (!different.isEmpty()) {
        log.error("The following dependencies differ:");
        different.forEach(line -> log.error("  " + line));
      }
      if (!added.isEmpty()) {
        log.error("Extraneous dependencies:");
        added.forEach(line -> log.error("  " + line));
      }
    }
  }
}
