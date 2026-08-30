import assert from "node:assert/strict";
import test from "node:test";

import {
  INTRO_VIDEO,
  introVideoDrawerMarkup,
  setIntroVideoOpen,
} from "./video-drawer.js";

test("publishes one stable introduction-video contract", () => {
  assert.deepEqual(INTRO_VIDEO, {
    title: "ModelJars in 3 minutes",
    duration: "3:06",
    source: "/media/modeljars-intro.mp4",
    poster: "/media/modeljars-intro.webp",
    page: "/intro/",
  });
});

test("renders a collapsed thumbnail launcher and accessible non-modal drawer", () => {
  const markup = introVideoDrawerMarkup();

  assert.match(markup, /id="intro-video-teaser"/);
  assert.match(markup, /aria-controls="intro-video-drawer"/);
  assert.match(markup, /class="intro-video-thumbnail"/);
  assert.match(markup, /Watch ModelJars in 3 minutes/);
  assert.match(markup, /id="intro-video-drawer"/);
  assert.match(markup, /aria-labelledby="intro-video-title"/);
  assert.match(markup, /aria-hidden="true"/);
  assert.match(markup, /preload="metadata"/);
  assert.match(markup, /poster="\/media\/modeljars-intro\.webp"/);
  assert.match(markup, /src="\/media\/modeljars-intro\.mp4"/);
  assert.match(markup, /href="\/intro\/"/);
  assert.doesNotMatch(markup, /role="dialog"|aria-modal|autoplay/);
});

test("opening and closing synchronizes accessibility state and playback", () => {
  const classes = new Set();
  const drawer = {
    classList: {
      toggle(name, enabled) {
        if (enabled) classes.add(name);
        else classes.delete(name);
      },
    },
    setAttribute(name, value) {
      this[name] = value;
    },
  };
  const teaser = {
    setAttribute(name, value) {
      this[name] = value;
    },
  };
  let pauses = 0;
  const video = { pause: () => pauses++ };

  setIntroVideoOpen({ drawer, teaser, video }, true);
  assert.equal(classes.has("open"), true);
  assert.equal(drawer["aria-hidden"], "false");
  assert.equal(teaser["aria-expanded"], "true");
  assert.equal(pauses, 0);

  setIntroVideoOpen({ drawer, teaser, video }, false);
  assert.equal(classes.has("open"), false);
  assert.equal(drawer["aria-hidden"], "true");
  assert.equal(teaser["aria-expanded"], "false");
  assert.equal(pauses, 1);
});
