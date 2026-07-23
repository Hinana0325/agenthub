#!/usr/bin/env bash
#
# check-version-sync.sh
#
# Verifies that the version defined in version.properties (the single source of
# truth) is consistently mirrored in the duplicated locations:
#
#   - ios/project.yml           (2 targets x 2 fields:
#                                 MARKETING_VERSION / CURRENT_PROJECT_VERSION)
#   - android/app/build.gradle  (fallback values in the ?: '...' expressions)
#
# Exits 0 when every reference matches the authoritative source, 1 on any drift.
#
# Usage (from repo root):  bash scripts/check-version-sync.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

VERSION_PROPS="$REPO_ROOT/version.properties"
IOS_YML="$REPO_ROOT/ios/project.yml"
ANDROID_GRADLE="$REPO_ROOT/android/app/build.gradle"

failures=0

# ---------------------------------------------------------------------------
# check_field FILE LINENO FIELD ACTUAL EXPECTED
#   Prints a mismatch (or not-found) line and bumps $failures.
#   Returns 1 when a problem was reported, 0 when the field is in sync.
# ---------------------------------------------------------------------------
check_field() {
    local file="$1" lineno="$2" field="$3" actual="$4" expected="$5"
    if [[ -z "$lineno" ]]; then
        echo "✗ ${file}: ${field} not found, expected \"${expected}\""
        failures=$((failures + 1))
        return 1
    fi
    if [[ "$actual" != "$expected" ]]; then
        echo "✗ ${file}:${lineno} has ${field}=\"${actual}\", expected \"${expected}\""
        failures=$((failures + 1))
        return 1
    fi
    return 0
}

# ---------------------------------------------------------------------------
# 0. Sanity: ensure the three files exist.
# ---------------------------------------------------------------------------
for f in "$VERSION_PROPS" "$IOS_YML" "$ANDROID_GRADLE"; do
    if [[ ! -f "$f" ]]; then
        echo "✗ File not found: $f"
        exit 1
    fi
done

# ---------------------------------------------------------------------------
# 1. Authoritative source: version.properties
#    Read with grep/sed — do NOT source the file (it has a comment header).
# ---------------------------------------------------------------------------
versionName=$(grep -E '^versionName=' "$VERSION_PROPS" | sed -E 's/^versionName=(.*)$/\1/' | head -1 || true)
versionCode=$(grep -E '^versionCode=' "$VERSION_PROPS" | sed -E 's/^versionCode=(.*)$/\1/' | head -1 || true)

if [[ -z "$versionName" || -z "$versionCode" ]]; then
    echo "✗ version.properties: could not parse versionName/versionCode"
    echo "  (got versionName=\"$versionName\" versionCode=\"$versionCode\")"
    exit 1
fi

echo "✓ version.properties: versionName=$versionName versionCode=$versionCode"

# ---------------------------------------------------------------------------
# 2. ios/project.yml — main target (1st occurrence) & Widget target (2nd).
#    Each line looks like:  MARKETING_VERSION: "4.8.0"
#    extract_yml_field KEY OCCURRENCE  ->  prints "lineno<TAB>value"
# ---------------------------------------------------------------------------
extract_yml_field() {
    local key="$1"
    local occurrence="$2"
    local match lineno rawvalue value
    match=$(grep -nE "^[[:space:]]*${key}:" "$IOS_YML" | sed -n "${occurrence}p" || true)
    if [[ -z "$match" ]]; then
        printf '%s\t%s\n' '' ''
        return
    fi
    lineno=$(printf '%s' "$match" | sed -E 's/^([0-9]+):.*/\1/')
    rawvalue=$(printf '%s' "$match" | sed -E "s/^[0-9]+:[[:space:]]*${key}:[[:space:]]*//")
    # strip optional surrounding double quotes (handles quoted & unquoted values)
    value=$(printf '%s' "$rawvalue" | sed -E 's/^"(.*)"$/\1/' | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//')
    printf '%s\t%s\n' "$lineno" "$value"
}

IFS=$'\t' read -r main_mv_lineno  main_mv  < <(extract_yml_field MARKETING_VERSION        1)
IFS=$'\t' read -r main_cpv_lineno main_cpv < <(extract_yml_field CURRENT_PROJECT_VERSION 1)
IFS=$'\t' read -r widget_mv_lineno  widget_mv  < <(extract_yml_field MARKETING_VERSION        2)
IFS=$'\t' read -r widget_cpv_lineno widget_cpv < <(extract_yml_field CURRENT_PROJECT_VERSION 2)

main_ok=1
check_field "ios/project.yml" "$main_mv_lineno"  "MARKETING_VERSION"        "$main_mv"  "$versionName" || main_ok=0
check_field "ios/project.yml" "$main_cpv_lineno" "CURRENT_PROJECT_VERSION" "$main_cpv" "$versionCode" || main_ok=0
if [[ $main_ok -eq 1 ]]; then
    echo "✓ ios/project.yml (main target): MARKETING_VERSION=$main_mv CURRENT_PROJECT_VERSION=$main_cpv"
fi

widget_ok=1
check_field "ios/project.yml" "$widget_mv_lineno"  "MARKETING_VERSION"        "$widget_mv"  "$versionName" || widget_ok=0
check_field "ios/project.yml" "$widget_cpv_lineno" "CURRENT_PROJECT_VERSION" "$widget_cpv" "$versionCode" || widget_ok=0
if [[ $widget_ok -eq 1 ]]; then
    echo "✓ ios/project.yml (Widget target): MARKETING_VERSION=$widget_mv CURRENT_PROJECT_VERSION=$widget_cpv"
fi

# ---------------------------------------------------------------------------
# 3. android/app/build.gradle — fallback values in the ?: '...' expressions.
#    Lines look like:
#      def vCode = (versionProps.getProperty('versionCode') ?: '42').toInteger()
#      def vName = versionProps.getProperty('versionName') ?: '4.8.0'
# ---------------------------------------------------------------------------
vcode_match=$(grep -nE "getProperty\('versionCode'\)" "$ANDROID_GRADLE" | head -1 || true)
vname_match=$(grep -nE "getProperty\('versionName'\)" "$ANDROID_GRADLE" | head -1 || true)

vcode_lineno=$(printf '%s' "$vcode_match" | sed -E 's/^([0-9]+):.*/\1/' || true)
vcode_fallback=$(printf '%s' "$vcode_match" | sed -E "s/.*[?]: *'([^']*)'.*/\1/" || true)
vname_lineno=$(printf '%s' "$vname_match" | sed -E 's/^([0-9]+):.*/\1/' || true)
vname_fallback=$(printf '%s' "$vname_match" | sed -E "s/.*[?]: *'([^']*)'.*/\1/" || true)

android_ok=1
check_field "android/app/build.gradle" "$vcode_lineno" "versionCode fallback" "$vcode_fallback" "$versionCode" || android_ok=0
check_field "android/app/build.gradle" "$vname_lineno" "versionName fallback" "$vname_fallback" "$versionName" || android_ok=0
if [[ $android_ok -eq 1 ]]; then
    echo "✓ android/app/build.gradle fallback: versionName=$vname_fallback versionCode=$vcode_fallback"
fi

# ---------------------------------------------------------------------------
# 4. Summary
# ---------------------------------------------------------------------------
if [[ $failures -eq 0 ]]; then
    echo "All version references are in sync."
    exit 0
fi

echo "Version drift detected: $failures field(s) out of sync. Fix the line(s) marked ✗ above."
exit 1
