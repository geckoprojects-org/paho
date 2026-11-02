#!/bin/bash

# --- Konfiguration ---
BASE_URL="https://ossrh-staging-api.central.sonatype.com" # Extracted base URL
API_ENDPOINT="${BASE_URL}/manual/search/repositories"
# UPLOAD_API_ENDPOINT is now the base path before the dynamic repository key
UPLOAD_BASE_API_ENDPOINT="${BASE_URL}/manual/upload/repository"

# Your Central Portal credentials
# These variables are expected to be set as environment variables before running the script
# e.g., in GitHub Actions secrets or locally via `export CS_USERNAME=...`
# CS_USERNAME="your_central_username"     # (Removed hardcoded value)
# CS_PASSWORD="your_central_password" # (Removed hardcoded value)

# --- Check for required environment variables ---
if [ -z "${CS_USERNAME}" ]; then
  echo "Error: The environment variable CS_USERNAME is not set." >&2
  exit 1
fi

if [ -z "${CS_PASSWORD}" ]; then
  echo "Error: The environment variable CS_PASSWORD is not set." >&2
  exit 1
fi

# --- Function to handle 401 (Unauthorized) errors ---
# Parameters:
# $1: Short description of the action performed (e.g., "fetching repository key")
# $2: The JSON response from the server (not printed here)
handle_401_error() {
  local action_desc="$1"
  # local json_resp="$2" # JSON response is not directly printed, but parameter remains for consistency
  echo "Error during ${action_desc} (HTTP 401 - Unauthorized): Authentication failed." >&2
  echo "Please check your username and password for the Central Portal." >&2
  exit 1
}

# --- Function to validate the HTTP status code ---
# Parameters:
# $1: The extracted HTTP status code
# $2: The full mixed output from Curl (for more detailed error message)
# $3: Short description of the action performed (e.g., "fetching repository key")
validate_http_status() {
  local status="$1"
  local mixed_output="$2"
  local action_desc="$3"

  if [ -z "$status" ] || ! [[ "$status" =~ ^[0-9]+$ ]]; then
    echo "Error during ${action_desc}: Curl could not receive a valid HTTP status code (3-digit). Output: '$mixed_output'" >&2
    exit 1
  fi
}

# --- Function to check Curl's exit code ---
# Parameters:
# $1: Curl's exit code ($?)
# $2: The full mixed output from Curl (if available)
# $3: Short description of the action performed (e.g., "fetching repository key")
check_curl_exit_code() {
  local exit_code="$1"
  local mixed_output="$2"
  local action_desc="$3"

  if [ "$exit_code" -ne 0 ]; then
    echo "Error during ${action_desc}: Curl could not complete the request (Exit Code: $exit_code)." >&2
    echo "Possible causes: Network issue, invalid URL, or authentication completely failed." >&2
    echo "Full Curl response (if available): '$mixed_output'" >&2
    exit 1
  fi
}

# --- Check if GROUP_ID was passed as a parameter ---
if [ -z "$1" ]; then
  echo "Error: The GROUP_ID must be passed as the first parameter." >&2
  echo "Usage: $0 <GROUP_ID>" >&2
  exit 1
fi

GROUP_ID="$1" # The first command-line argument is assigned to GROUP_ID

# --- Global variable for the result ---
REPO_KEY=""

# --- Perform curl request to fetch REPO_KEY ---
# Prepare URL parameters
QUERY_PARAMS="profile_id=${GROUP_ID}&state=open&ip=any"
FULL_SEARCH_URL="${API_ENDPOINT}?${QUERY_PARAMS}"

# Execute curl and capture the entire output (JSON + status code)
# -s: Silent mode (suppresses progress meter and error messages to stderr)
# -w "%{http_code}": Writes the HTTP status code to stdout after the body.
# 2>/dev/null: Redirects any other stderr messages from curl to /dev/null.
# The entire stdout (JSON + status code) is captured in MIXED_OUTPUT.
MIXED_OUTPUT=$(curl -s -w "%{http_code}" --user "${CS_USERNAME}:${CS_PASSWORD}" "${FULL_SEARCH_URL}" 2>/dev/null)
CURL_EXIT_CODE=$?

# --- Separate HTTP status code and JSON output from the search response (Inline handling) ---
if [ ${#MIXED_OUTPUT} -ge 3 ]; then
  HTTP_STATUS="${MIXED_OUTPUT: -3}" # HTTP status code is the last 3 characters
  JSON_OUTPUT="${MIXED_OUTPUT:0:${#MIXED_OUTPUT}-3}" # JSON output is the string without the last 3 characters
else
  HTTP_STATUS=""
  JSON_OUTPUT="$MIXED_OUTPUT" # Otherwise, the entire output is what remains.
fi

# --- Error handling for the search request ---
# Check if curl itself had an error
check_curl_exit_code "$CURL_EXIT_CODE" "$MIXED_OUTPUT" "fetching the repository key"

# Validate the HTTP status code
validate_http_status "$HTTP_STATUS" "$MIXED_OUTPUT" "fetching the repository key"

# Specific error handling for the search
if [ "$HTTP_STATUS" -eq 401 ]; then
  handle_401_error "fetching the repository key" "$JSON_OUTPUT"
elif [ "$HTTP_STATUS" -ne 200 ]; then
  echo "Error fetching the repository key: HTTP status code $HTTP_STATUS received." >&2
  echo "Server response:" >&2
  echo "$JSON_OUTPUT" >&2
  exit 1
fi

# --- Parse search JSON and set REPO_KEY ---
# Check if JSON_OUTPUT is empty before sending it to jp
if [ -z "$JSON_OUTPUT" ]; then
  echo "The API response for the repository search was empty or jp received no data to parse." >&2
else
  REPO_KEY=$(echo "$JSON_OUTPUT" | jp -u "repositories[0].key || ''")
fi

# If no REPO_KEY was found, exit the script (with failure)
if [ -z "$REPO_KEY" ]; then
  echo "No repositories found with the specified criteria for GROUP_ID '$GROUP_ID', cannot proceed with upload."
  exit 1
fi

echo "First repository key for '$GROUP_ID' found: $REPO_KEY"

# This sets the REPO_KEY variable for subsequent steps in GitHub Actions
echo "REPO_KEY=$REPO_KEY" >> "$GITHUB_ENV"

# --- Start manual upload (POST with query and path parameters) ---
# repository_key is now a path parameter, publishing_type is a query parameter.
UPLOAD_QUERY_PARAMS="publishing_type=automatic"
# Construct the URL with the repository key as a segment and publishing_type as a query parameter
FULL_UPLOAD_URL="${UPLOAD_BASE_API_ENDPOINT}/${REPO_KEY}?${UPLOAD_QUERY_PARAMS}"

echo "Starting manual upload for repository key: '$REPO_KEY'..."

# Execute curl for the upload
# -X POST: Specifies the POST method
# No '-H "Content-Type: ..."' or '--data' is required as parameters are in the URL.
UPLOAD_MIXED_OUTPUT=$(curl -s -w "%{http_code}" -X POST \
                            --user "${CS_USERNAME}:${CS_PASSWORD}" \
                            "${FULL_UPLOAD_URL}" 2>/dev/null)
UPLOAD_CURL_EXIT_CODE=$?

# --- Separate HTTP status code and JSON output from the upload response (Inline handling) ---
if [ ${#UPLOAD_MIXED_OUTPUT} -ge 3 ]; then
  UPLOAD_HTTP_STATUS="${UPLOAD_MIXED_OUTPUT: -3}"
  UPLOAD_JSON_OUTPUT="${UPLOAD_MIXED_OUTPUT:0:${#UPLOAD_MIXED_OUTPUT}-3}"
else
  UPLOAD_HTTP_STATUS=""
  UPLOAD_JSON_OUTPUT="$UPLOAD_MIXED_OUTPUT"
fi

# --- Error handling for the upload ---
check_curl_exit_code "$UPLOAD_CURL_EXIT_CODE" "$UPLOAD_MIXED_OUTPUT" "manual upload"

# Validate the HTTP status code for the upload
validate_http_status "$UPLOAD_HTTP_STATUS" "$UPLOAD_MIXED_OUTPUT" "manual upload"

# Specific error handling based on HTTP status code
case "$UPLOAD_HTTP_STATUS" in
  200)
    echo "Manual upload successfully initiated."
    echo "API response for upload: $UPLOAD_JSON_OUTPUT"
    exit 0 # Script finished successfully
    ;;
  400)
    ERROR_MESSAGE=$(echo "$UPLOAD_JSON_OUTPUT" | jp -u "error || 'No error details available.'")
    echo "Error during manual upload (HTTP 400 - Bad Request): $ERROR_MESSAGE" >&2
    echo "Server response: $UPLOAD_JSON_OUTPUT" >&2
    exit 1
    ;;
  401)
    handle_401_error "manual upload" "$UPLOAD_JSON_OUTPUT"
    ;;
  *) # All other HTTP error codes
    echo "Error during manual upload: Unexpected HTTP status code $UPLOAD_HTTP_STATUS received." >&2
    echo "Server response:" >&2
    echo "$UPLOAD_JSON_OUTPUT" >&2
    exit 1
    ;;
esac

