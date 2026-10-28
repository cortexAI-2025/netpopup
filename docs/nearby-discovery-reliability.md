# Nearby discovery and queued messages — 2026-10-28

## Report

Two physical Samsung phones running NetPopUp do not find each other; a public-room
message remains Queued. No Nearby SDK status or radio state was supplied, so the
specific physical-device cause is not established.

## Reproducible scheduling defect

The previous app used fixed 20-second active / 40-second idle windows. Phones
started 30 seconds apart have no active overlap, indefinitely. The application
now selects continuous discovery while the mesh service is enabled. A successful
scan/advertising session keeps its anonymous endpoint name until stopped rather
than rotating it during a handshake. Failed starts are retried after 20 seconds.
The opt-in duty-cycle transport configuration remains available for future use.
Continuous radio discovery costs more battery than the former duty cycle; turn
off Mesh service when relaying is not wanted.

The lower anonymous name still gets first chance to initiate. After 3 seconds,
a device may initiate from the other side too if no connection is pending: a
one-sided discovery callback must not leave both phones waiting forever.

Pending handshakes now expire after 15 seconds and retry a still-discovered
endpoint after a 2-second delay. Late success callbacks cannot resurrect an
expired request. Actual connection callbacks still determine connected state.

## Diagnostics

The branded chat header previously ignored its subtitle. It now shows mesh
stopped / searching / connected count. Settings separately show advertising and
discovery status, discovered endpoints, pending handshakes, radio links and last
SDK failure code. Verified mesh peers remain a separate count from radio links.
No endpoint names, payloads or private identity keys enter diagnostics.

## Physical test

On both phones: enable Bluetooth and Wi-Fi; on Android 10/11/12 enable Location;
grant Nearby/location permissions requested by NetPopUp; enable Mesh service.
Keep both apps open within a metre for the first test. Same-Wi-Fi connectivity
alone does not establish a NetPopUp peer connection.

If discovery still fails, collect both Settings screens: advertising/discovery,
Found/Connecting/Radio links, and the last Nearby SDK status. These distinguish
permissions/radio startup failure, discovery failure, connection failure, and a
radio link whose mesh handshake has not verified. Emulators and fake transports
cannot establish actual Samsung Bluetooth/Wi-Fi interoperability.
