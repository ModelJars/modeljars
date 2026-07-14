export function resolveTheme(storedTheme, systemPrefersDark) {
  if (storedTheme === "light" || storedTheme === "dark") return storedTheme;
  return systemPrefersDark ? "dark" : "light";
}

export function nextTheme(theme) {
  return theme === "dark" ? "light" : "dark";
}

export function initializeTheme(toggle, storage = window.localStorage) {
  const systemQuery = window.matchMedia("(prefers-color-scheme: dark)");
  let theme = resolveTheme(storage.getItem("modeljars-theme"), systemQuery.matches);

  const apply = () => {
    document.documentElement.dataset.theme = theme;
    if (toggle) {
      toggle.setAttribute("aria-label", `Use ${nextTheme(theme)} theme`);
      toggle.setAttribute("title", `Use ${nextTheme(theme)} theme`);
      toggle.dataset.theme = theme;
    }
  };

  toggle?.addEventListener("click", () => {
    theme = nextTheme(theme);
    storage.setItem("modeljars-theme", theme);
    apply();
  });

  apply();
  return theme;
}
