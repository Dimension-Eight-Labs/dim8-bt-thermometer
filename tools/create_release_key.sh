#!/usr/bin/env bash
# Creates the one permanent DIM8 BT Thermometer release signing key.
#
# Android only installs an update when it is signed by the same key, so this
# script refuses to replace an existing keystore. The password is read from the
# terminal and passed to keytool through its environment, which keeps it out of
# shell history and process arguments. Both output files stay outside the
# repository, in a folder only this user can read.
set -euo pipefail
umask 077

SIGNING_DIR="$HOME/.config/dim8-bt-thermometer"
KEYSTORE="$SIGNING_DIR/dim8-bt-thermometer-release.p12"
PROPERTIES="$SIGNING_DIR/signing.properties"
KEY_ALIAS="dim8-bt-thermometer-release"
CERTIFICATE_NAME="CN=DIM8 BT Thermometer, O=Dimension Eight Labs"
# About 27 years, which covers the life of the app and its future updates.
VALIDITY_DAYS=10000

if [[ -e "$KEYSTORE" || -e "$PROPERTIES" ]]; then
    echo "A signing file already exists in $SIGNING_DIR. Nothing was changed." >&2
    exit 1
fi

read -rsp "New keystore password (at least 12 characters): " password
echo
read -rsp "Repeat password: " repeated
echo

if [[ "$password" != "$repeated" ]]; then
    echo "The passwords do not match. Nothing was changed." >&2
    exit 1
fi
if (( ${#password} < 12 )); then
    echo "The password is too short. Nothing was changed." >&2
    exit 1
fi
# signing.properties is read as Latin-1 and trims leading spaces, so limiting the
# password to printable ASCII with no leading space keeps it from being altered.
LC_ALL=C
if [[ "$password" == *[![:print:]]* || "$password" == " "* ]]; then
    echo "Use printable ASCII with no leading space. Nothing was changed." >&2
    exit 1
fi

mkdir -p "$SIGNING_DIR"
chmod 700 "$SIGNING_DIR"

export DIM8_RELEASE_KEY_PASSWORD="$password"
keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storetype PKCS12 \
    -storepass:env DIM8_RELEASE_KEY_PASSWORD \
    -alias "$KEY_ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -sigalg SHA256withRSA \
    -validity "$VALIDITY_DAYS" \
    -dname "$CERTIFICATE_NAME"

# Backslash is the escape character in Java properties files.
escaped="${password//\\/\\\\}"
{
    printf 'storeFile=%s\n' "$KEYSTORE"
    printf 'storePassword=%s\n' "$escaped"
    printf 'keyAlias=%s\n' "$KEY_ALIAS"
    printf 'keyPassword=%s\n' "$escaped"
} > "$PROPERTIES"
chmod 600 "$KEYSTORE" "$PROPERTIES"

echo "Created $KEYSTORE"
keytool -list -keystore "$KEYSTORE" -storepass:env DIM8_RELEASE_KEY_PASSWORD -alias "$KEY_ALIAS" |
    grep 'SHA-256'
echo "Back up the keystore to encrypted storage and save the password in your password manager."
