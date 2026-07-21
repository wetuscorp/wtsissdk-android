#!/usr/bin/env bash
set -euo pipefail

ruby -ryaml -e '
  workflow = YAML.load_file(".github/workflows/release.yml")
  release = workflow.fetch("jobs").fetch("release")
  steps = release.fetch("steps")

  root_key = release.fetch("env").fetch("ORG_GRADLE_PROJECT_wtsExperienceRootPublicKey")
  abort "Release must inject the Experience root public key" unless root_key.include?("WTS_EXPERIENCE_ROOT_PUBLIC_KEY")

  verify = steps.find { |step| step["name"] == "Verify tag and contracts" }
  abort "Release must validate the Experience root public key" unless verify && verify.fetch("run").include?("openssl pkey -pubin")

  registry = steps.find { |step| step["id"] == "maven_registry" }
  abort "Missing Maven Central registry preflight" unless registry
  run = registry.fetch("run")
  abort "Registry preflight must inspect the exact POM URL" unless run.include?("repo.maven.apache.org/maven2/co/wetus/wts-sdk")
  abort "Registry preflight must distinguish existing artifacts" unless run.include?("published=true") && run.include?("published=false")

  publish = steps.find { |step| step["name"] == "Publish Maven artifact" }
  abort "Missing Maven publish step" unless publish
  abort "Maven publish must be conditional on registry absence" unless publish["if"] == "steps.maven_registry.outputs.published != '\''true'\''"
  abort "Maven publish command is missing" unless publish.fetch("run").include?(":sdk:publishToMavenCentral")
'
