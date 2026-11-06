# Contributing

Thanks for your interest in this project! It is a Gecko projects build of the
[Eclipse Paho Java client](https://github.com/eclipse-paho/paho.mqtt.java); bug fixes and
improvements are welcome as pull requests against `snapshot`, the default branch; `main` holds
the releases.

- Keep changes focused; one topic per pull request.
- Sign off your commits (`git commit -s`, Developer Certificate of Origin).
- New source files carry the Paho licence header (EPL-2.0 and EDL-1.0); the CI checks it.
- Add JUnit tests. Tests that need no broker run in `./gradlew build`; tests against a broker
  run with `test/broker/start-broker.sh` and `./gradlew brokerTest` (see the README).

Fixes that also apply to upstream Paho are offered upstream as well; contributions to upstream
itself follow its own rules (Eclipse Contributor Agreement, see the upstream
[CONTRIBUTING.md](https://github.com/eclipse-paho/paho.mqtt.java/blob/master/CONTRIBUTING.md)).
