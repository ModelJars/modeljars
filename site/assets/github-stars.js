const REPOSITORY = "ModelJars/modeljars";
const REPOSITORY_API = `https://api.github.com/repos/${REPOSITORY}`;
const CACHE_KEY = "modeljars-github-stars-v1";
const CACHE_TTL_MILLIS = 60 * 60 * 1000;

export function formatStarCount(count, locale) {
  return new Intl.NumberFormat(locale).format(count);
}

export async function fetchStarCount(fetchImplementation = globalThis.fetch) {
  const response = await fetchImplementation(REPOSITORY_API, {
    headers: {
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
    },
  });
  if (!response.ok) {
    throw new Error(`GitHub repository request failed: ${response.status}`);
  }
  const repository = await response.json();
  const count = repository.stargazers_count;
  if (!Number.isSafeInteger(count) || count < 0) {
    throw new Error("GitHub repository response has no valid stargazers_count");
  }
  return count;
}

export function readCachedStarCount(storage, now = Date.now()) {
  if (!storage) return null;
  try {
    const cached = JSON.parse(storage.getItem(CACHE_KEY));
    if (
      cached?.repository !== REPOSITORY ||
      !Number.isSafeInteger(cached.count) ||
      cached.count < 0 ||
      !Number.isFinite(cached.storedAt) ||
      now - cached.storedAt < 0 ||
      now - cached.storedAt > CACHE_TTL_MILLIS
    ) {
      return null;
    }
    return cached.count;
  } catch {
    return null;
  }
}

function cacheStarCount(storage, count, now = Date.now()) {
  if (!storage) return;
  try {
    storage.setItem(CACHE_KEY, JSON.stringify({ repository: REPOSITORY, count, storedAt: now }));
  } catch {
    // A blocked or full browser store must not hide the live count.
  }
}

function displayStarCount(button, countElement, count, locale) {
  const formatted = formatStarCount(count, locale);
  countElement.textContent = formatted;
  countElement.hidden = false;
  button.setAttribute("aria-label", `Star ModelJars on GitHub (${formatted} stars)`);
}

export async function initializeGitHubStars({
  root = document,
  fetchImplementation = globalThis.fetch,
  storage = globalThis.localStorage,
  locale = globalThis.navigator?.language,
} = {}) {
  const button = root.querySelector(".github-star-button");
  const countElement = root.querySelector("#github-star-count");
  if (!button || !countElement) return null;

  const cached = readCachedStarCount(storage);
  if (cached !== null) {
    displayStarCount(button, countElement, cached, locale);
    return cached;
  }

  button.setAttribute("aria-busy", "true");
  try {
    const count = await fetchStarCount(fetchImplementation);
    cacheStarCount(storage, count);
    displayStarCount(button, countElement, count, locale);
    return count;
  } catch {
    return null;
  } finally {
    button.removeAttribute("aria-busy");
  }
}

if (typeof document !== "undefined") {
  initializeGitHubStars();
}
