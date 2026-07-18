#!/usr/bin/env bash
set -euo pipefail

ruby -ryaml -e '
  workflow = YAML.load_file(".github/workflows/release.yml")
  steps = workflow.fetch("jobs").fetch("release").fetch("steps")

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
