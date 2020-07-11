# FontMetrics

This repo is a light port to Java of [anafanafo](https://github.com/metabolize/anafanafo), which computes text width for 110 pt Verdana. Built with [MavenBadges](https://github.com/ggrandes/mavenbadges) in mind.

## Usage

```java
final SimpleFontMetrics metrics = SimpleFontMetrics.getInstance();
final int width = metrics.widthOf("Hello World!");
```

## License

All rights to Verdana are owned by Microsoft Corp.

The remainder of this project is licensed under the Apache-2.0 License.

---
Inspired in [anafanafo](https://github.com/metabolize/anafanafo), this code is Java-minimalistic version.
