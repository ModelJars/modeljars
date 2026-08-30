export const INTRO_VIDEO = Object.freeze({
  title: "ModelJars in 3 minutes",
  duration: "3:06",
  source: "/media/modeljars-intro.mp4",
  poster: "/media/modeljars-intro.webp",
  page: "/intro/",
});

export function introVideoDrawerMarkup(video = INTRO_VIDEO) {
  return `
    <button
      id="intro-video-teaser"
      class="intro-video-teaser"
      type="button"
      aria-expanded="false"
      aria-controls="intro-video-drawer">
      <span class="intro-video-thumbnail" aria-hidden="true">
        <img src="${video.poster}" alt="" width="96" height="54">
        <span class="intro-video-play"></span>
      </span>
      <span>
        <strong>Watch ModelJars in 3 minutes</strong>
        <small>Catalog, CLI, embeddings, and chat</small>
      </span>
      <span class="intro-video-duration">${video.duration}</span>
    </button>
    <aside
      id="intro-video-drawer"
      class="intro-video-drawer"
      aria-labelledby="intro-video-title"
      aria-hidden="true"
      inert>
      <div class="intro-video-heading">
        <div>
          <p class="eyebrow">Three-minute overview</p>
          <h2 id="intro-video-title">${video.title}</h2>
        </div>
        <button id="intro-video-close" class="intro-video-close" type="button" aria-label="Close introduction video">&times;</button>
      </div>
      <video controls preload="metadata" poster="${video.poster}">
        <source src="${video.source}" type="video/mp4">
        Your browser does not support HTML video.
      </video>
      <div class="intro-video-footer">
        <span>${video.duration}</span>
        <a href="${video.page}">Open the shareable video page</a>
      </div>
    </aside>`;
}

export function setIntroVideoOpen({ drawer, teaser, video }, open) {
  drawer.classList.toggle("open", open);
  drawer.setAttribute("aria-hidden", String(!open));
  drawer.inert = !open;
  teaser.setAttribute("aria-expanded", String(open));
  teaser.classList?.toggle("drawer-open", open);
  if (!open) video?.pause();
}

export function initializeIntroVideoDrawer({ documentObject = document } = {}) {
  if (documentObject.querySelector("#intro-video-drawer")) return;

  documentObject.body.insertAdjacentHTML("beforeend", introVideoDrawerMarkup());
  const teaser = documentObject.querySelector("#intro-video-teaser");
  const drawer = documentObject.querySelector("#intro-video-drawer");
  const close = documentObject.querySelector("#intro-video-close");
  const video = drawer.querySelector("video");
  const elements = { drawer, teaser, video };

  teaser.addEventListener("click", () =>
    setIntroVideoOpen(elements, !drawer.classList.contains("open")),
  );
  close.addEventListener("click", () => {
    setIntroVideoOpen(elements, false);
    teaser.focus();
  });
  documentObject.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && drawer.classList.contains("open")) {
      setIntroVideoOpen(elements, false);
      teaser.focus();
    }
  });
}

if (typeof document !== "undefined") initializeIntroVideoDrawer();
