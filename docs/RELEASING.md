# Releasing

Releases are published on **Maven Central** (`org.geckoprojects.paho`). Jenkins builds, signs and
uploads them; GitHub carries the tag and a GitHub release with the same files.

| Branch / ref | Who | What |
|---|---|---|
| `snapshot` (default branch) | Jenkins | `<version>-SNAPSHOT` to the DIM Nexus and the Maven Central snapshot repository |
| `main` | Jenkins | the release: signed bundles to the DIM Nexus and, as one deployment, to the Sonatype Central Portal |
| tag `v<version>` | GitHub Actions (`release.yml`) | GitHub release with the jars, poms and `.asc` signatures downloaded from Maven Central |

The version comes from `base-version` in `cnf/ext/fennec.bnd`.

## Steps

1. **Release notes.** Write `docs/releases/<version>.md` on `snapshot` (what changed, links to
   issues and to `docs/UPSTREAM.md` at the tag). Update the version mentions in `README.md` and
   `docs/GUIDE.md`.
2. **Release pull request** from `snapshot` to `main`. CI (build, licence headers, CodeQL) must be
   green. Merge it with a **merge commit**, so that `main` keeps the history of `snapshot`.
3. **Jenkins** builds `main` with `DO_RELEASE=true`: bare version, signed, to the DIM Nexus and into
   `dist/bundles`, which it uploads to the Central Portal and follows until validated.
4. **Publish in the Portal.** The deployment waits at
   [central.sonatype.com/publishing](https://central.sonatype.com/publishing) (`USER_MANAGED`);
   check it and press *Publish*. Maven Central serves it after 10 to 30 minutes.
5. **Tag** the merge commit on `main` and push the tag:
   ```
   git fetch origin
   git tag -s v<version> -m "Paho Java client <version>" origin/main
   git push origin v<version>
   ```
   `release.yml` waits until the version is on Maven Central (at most about 2.5 h), checks the
   downloads against Central's checksums and creates the GitHub release with the notes of step 1.
   Should it time out, run it again from the Actions tab with the tag as input.
6. **Next version.** Raise `base-version` in `cnf/ext/fennec.bnd` on `snapshot` (e.g. to the next
   patch version), so that snapshots no longer carry the released version.

A release of a version that is on Maven Central cannot be replaced; a fix needs a new version.
