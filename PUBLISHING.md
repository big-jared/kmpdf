# Publishing Guide

This guide explains how to publish the KmPDF library to Maven Central.

## Prerequisites

### 1. Sonatype Account

1. Create an account at https://issues.sonatype.org/
2. Create a JIRA ticket to claim your group ID (`io.github.bigboyapps`)
   - Follow instructions at: https://central.sonatype.org/publish/publish-guide/
   - You'll need to verify GitHub repository ownership
3. Wait for approval (usually 1-2 business days)

### 2. GPG Key for Signing

Generate a GPG key to sign your artifacts:

```bash
# Generate key
gpg --gen-key

# List your keys
gpg --list-secret-keys --keyid-format=long

# Export your private key (replace KEY_ID with your actual key ID)
gpg --armor --export-secret-keys KEY_ID

# Get your key password (you set this during generation)
```

### 3. Configure GitHub Secrets

Add the following secrets to your GitHub repository (Settings → Secrets and variables → Actions):

1. **OSSRH_USERNAME**: Your Sonatype JIRA username
2. **OSSRH_PASSWORD**: Your Sonatype JIRA password
3. **SIGNING_KEY**: Your GPG private key (entire ASCII-armored output from `gpg --armor --export-secret-keys`)
4. **SIGNING_PASSWORD**: Your GPG key password

## Publishing Process

### Option 1: Publish via GitHub Release (Recommended)

1. Update version in `kmpdf/build.gradle.kts`:
   ```kotlin
   version = "1.0.1"  // Change this
   ```

2. Commit and push changes:
   ```bash
   git add kmpdf/build.gradle.kts
   git commit -m "Bump version to 1.0.1"
   git push
   ```

3. Create a GitHub release:
   - Go to your repository → Releases → Create new release
   - Tag: `v1.0.1`
   - Title: `v1.0.1`
   - Description: Release notes
   - Click "Publish release"

4. The publish workflow will automatically run and deploy to Maven Central

### Option 2: Manual Trigger

1. Go to Actions → Publish to Maven Central → Run workflow
2. Enter the version number (e.g., `1.0.1`)
3. Click "Run workflow"

### Option 3: Local Publishing (Testing)

To test locally before publishing:

```bash
# Set environment variables
export OSSRH_USERNAME="your-username"
export OSSRH_PASSWORD="your-password"
export SIGNING_KEY="$(cat path/to/private-key.asc)"
export SIGNING_PASSWORD="your-key-password"

# Publish
./gradlew :kmpdf:publishAllPublicationsToOSSRHRepository
```

## After Publishing

1. Log in to https://s01.oss.sonatype.org/
2. Click "Staging Repositories" in the left menu
3. Find your repository (usually named `iogithubbigboyapps-XXXX`)
4. Click "Close" to trigger validation
5. Wait for validation to complete
6. Click "Release" to publish to Maven Central
7. Your library will be available within 15-30 minutes

## Using Your Library

After publishing, users can add your library:

### Gradle (Kotlin DSL)
```kotlin
dependencies {
    implementation("io.github.bigboyapps:kmpdf:1.0.0")
}
```

### Gradle (Groovy)
```groovy
dependencies {
    implementation 'io.github.bigboyapps:kmpdf:1.0.0'
}
```

## Troubleshooting

### Publishing fails with "Unauthorized"
- Verify OSSRH credentials are correct
- Ensure your Sonatype JIRA ticket was approved

### Signing fails
- Check that SIGNING_KEY contains the full ASCII-armored key
- Verify SIGNING_PASSWORD is correct
- Ensure the key was exported with `--armor`

### Validation fails in Sonatype
- Check that POM has required fields (name, description, URL, license, developers, SCM)
- Ensure all artifacts are signed
- Verify group ID matches your approved namespace

## References

- [Maven Central Publishing Guide](https://central.sonatype.org/publish/publish-guide/)
- [Kotlin Multiplatform Publishing](https://kotlinlang.org/docs/multiplatform-publish-lib.html)
