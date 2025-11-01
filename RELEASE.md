# Release Process

This document outlines how to release a new version of KmPDF to Maven Central.

## Prerequisites

Ensure the following secrets are configured in GitHub Settings → Secrets and variables → Actions:

- `MAVEN_CENTRAL_USERNAME` - Your Maven Central username
- `MAVEN_CENTRAL_PASSWORD` - Your Maven Central password
- `SIGNING_KEY` - Your GPG signing key (armored)
- `SIGNING_KEY_ID` - Your GPG key ID (last 8 characters)
- `SIGNING_KEY_PASSWORD` - Your GPG key password

These are the same secrets used for the `motion-calendar` library.

## Release Steps

### Using the Release Script (Recommended)

1. Make sure you're on the `main` branch with all changes committed:
   ```bash
   git checkout main
   git pull origin main
   ```

2. Run the release script:
   ```bash
   ./scripts/release.sh
   ```

3. The script will:
   - Check that your working directory is clean
   - Show the current version
   - Prompt you for the new version (e.g., `1.0.1`, `1.1.0`, `2.0.0`)
   - Update `kmpdf/build.gradle.kts` with the new version
   - Generate a changelog from commits and PRs since the last release
   - Create a version bump commit
   - Create and push a version tag (e.g., `v1.0.1`)
   - Trigger the GitHub Actions publish workflow

4. Monitor the release:
   - Visit https://github.com/big-jared/kmpdf/actions
   - The "Publish to Maven Central" workflow will run
   - A GitHub Release will be created automatically
   - The library will be published to Maven Central

### Manual Release (Alternative)

If you prefer to release manually:

1. Update the version in `kmpdf/build.gradle.kts`:
   ```kotlin
   val libraryVersion = "x.y.z"
   ```

2. Commit the version bump:
   ```bash
   git add kmpdf/build.gradle.kts
   git commit -m "chore: bump version to x.y.z"
   git push origin main
   ```

3. Create and push a tag:
   ```bash
   git tag vx.y.z
   git push origin vx.y.z
   ```

4. The GitHub Actions workflow will automatically:
   - Build the library
   - Publish to Maven Central
   - Create a GitHub Release

## Versioning

We follow [Semantic Versioning](https://semver.org/):

- **Major** version (x.0.0): Breaking changes
- **Minor** version (0.x.0): New features, backwards compatible
- **Patch** version (0.0.x): Bug fixes, backwards compatible

## Changelog

The release script automatically generates a changelog including:
- Merged pull requests
- Individual commits
- Contributors

This changelog is saved to `CHANGELOG_vX.Y.Z.md` and should be added to the GitHub Release notes.

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

If you need to republish:
1. Delete the tag locally: `git tag -d vX.Y.Z`
2. Delete the tag remotely: `git push origin :refs/tags/vX.Y.Z`
3. Delete the GitHub Release (if created)
4. Re-run the release process

## After Release

1. Update the README with the new version in installation instructions
2. Announce the release on relevant channels
3. Close any related issues/milestones
