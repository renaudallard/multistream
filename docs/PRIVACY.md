# Privacy Policy — multistream

_Last updated: 22 June 2026_

multistream is an Android app that federates the catalogs of streaming services you
already use. This policy explains what the app does with data. The short version: **it has
no servers of its own, and none of your personal data is ever sent to the developer.**

## Data the app stores on your device

- **Login credentials and session tokens** for the streaming services you choose to sign
  into (e.g. Disney+, Plex, Netflix). These are kept in Android's encrypted storage
  (`EncryptedSharedPreferences`) and never leave your device except to authenticate
  directly with that service, which only happens when you initiate a login.
- **Watch history and progress** (watched/unwatched, next episode, watchlist,
  continue-watching). Stored only in a local database on your device.
- **Settings** such as per-service region and interface language. Stored locally.

All of this stays on your device. Logging out or clearing the app's data erases it.

## Data sent over the network

- **To each streaming service:** when you search, browse, or sign in, the app talks
  directly to that service's own API using your account. Your use of those services is
  governed by their own privacy policies.
- **To GitHub:** on launch the app asks GitHub whether a newer version of the app exists,
  so it can offer an update. This is a standard web request; GitHub may log the connection
  (e.g. IP address) as described in GitHub's privacy statement. No personal data from the
  app is included.

## Data the app does not do

- No analytics, advertising, or tracking SDKs of any kind.
- No developer-operated server, account, or backend — the developer receives nothing.
- No selling or sharing of personal data.
- The app requests only the `INTERNET` and `ACCESS_NETWORK_STATE` permissions.

## Children

The app is not directed at children and collects no data for that or any other audience.

## Changes

Updates to this policy will be posted at this page, with the date above changed.

## Contact

renaud@allard.it
