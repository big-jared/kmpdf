# Release Process

This document outlines how to release a new version of KmPDF to Maven Central.

## Prerequisites

Ensure the following secrets are configured in GitHub Settings → Secrets and variables → Actions:

- `MAVEN_CENTRAL_USERNAME` - Your Maven Central username
- `MAVEN_CENTRAL_PASSWORD` - Your Maven Central password
- `SIGNING_KEY` - Your GPG signing key (armored)
- `SIGNING_KEY_ID` - Your GPG key ID (last 8 characters)
- `SIGNING_KEY_PASSWORD` - Your GPG key password

## Release Steps

### Option 1: PR with Release Label (Recommended)

The easiest way to create a release is by merging a PR with the `release` label:

1. Create a PR with your changes
2. Add the `release` label to the PR
3. Merge the PR

The GitHub Actions workflow will automatically:
- Calculate the next version by incrementing the patch version (e.g., `1.0.0` → `1.0.1`)
- Run quality checks (detekt, tests)
- Update API dump files (`apiDump`)
- Generate Dokka documentation
- Update the version in `kmpdf/build.gradle.kts`
- Generate a changelog from git history
- Commit all changes (version bump + API dump)
- Create and push a git tag
- Create a GitHub release with the changelog
- Trigger the publish workflow to Maven Central

This is the recommended approach as it fully automates the release process in a clean CI environment.

### Option 2: Manual Workflow Dispatch

For custom version numbers (e.g., minor or major version bumps):

1. Go to [Actions → Create Release](https://github.com/big-jared/kmpdf/actions/workflows/release.yml)
2. Click "Run workflow"
3. Enter the specific version number (e.g., `1.1.0`, `2.0.0`)
4. Click "Run workflow"

The workflow will run the same automated steps as Option 1 but with your specified version.

## Versioning

We follow [Semantic Versioning](https://semver.org/):

- **Major** version (x.0.0): Breaking changes
- **Minor** version (0.x.0): New features, backwards compatible
- **Patch** version (0.0.x): Bug fixes, backwards compatible

The automated release workflow increments the **patch** version by default. Use manual workflow dispatch for minor/major bumps.

## What Happens During Release

1. **Quality Checks**: Detekt static analysis and all tests must pass
2. **API Dump**: Binary compatibility signatures are updated automatically
3. **Documentation**: Dokka HTML documentation is generated
4. **Version Update**: `kmpdf/build.gradle.kts` is updated with the new version
5. **Changelog**: Generated from git commits and PRs since the last tag
6. **Git Operations**: Changes are committed, tagged, and pushed to `main`
7. **GitHub Release**: Created with the generated changelog
8. **Maven Central**: Publish workflow is triggered to deploy the artifact

## Monitoring the Release

After triggering a release:

1. Visit https://github.com/big-jared/kmpdf/actions
2. Watch the "Create Release" workflow complete
3. Once successful, the "Publish to Maven Central" workflow will start
4. Verify the GitHub Release was created: https://github.com/big-jared/kmpdf/releases
5. After publishing completes, verify on Maven Central: https://central.sonatype.com/artifact/io.github.big-jared/kmpdf

## Troubleshooting

### Build Fails in GitHub Actions

- Check that all secrets are correctly configured
- Verify the Gradle build succeeds locally: `./gradlew :kmpdf:build`
- Check the Actions logs for specific errors

### Publishing Fails

- Ensure your Maven Central credentials are valid
- Verify your GPG key is not expired
- Check that the version doesn't already exist on Maven Central

### Release Already Exists

If you need to redo a release:
1. Delete the tag locally: `git tag -d vX.Y.Z`
2. Delete the tag remotely: `git push origin :refs/tags/vX.Y.Z`
3. Delete the GitHub Release (if created)
4. Reset main to before the release commit (if needed)
5. Re-run the release process

## After Release

1. Verify the release appears on Maven Central (can take ~30 minutes)
2. Check that documentation was deployed to GitHub Pages: https://big-jared.github.io/kmpdf/
3. Update dependent projects to use the new version
4. Announce the release on relevant channels
5. Close any related issues/milestones

## Documentation

API documentation is automatically published to GitHub Pages on every release. The workflow:
1. Generates Dokka HTML documentation
2. Deploys to https://big-jared.github.io/kmpdf/

You can also manually trigger documentation deployment from the [Actions tab](https://github.com/big-jared/kmpdf/actions/workflows/docs.yml).
