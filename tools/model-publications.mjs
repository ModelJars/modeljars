function assertCatalog(document, label) {
  if (
    document === null ||
    typeof document !== "object" ||
    document.schemaVersion !== 2 ||
    !Array.isArray(document.models)
  ) {
    throw new Error(`${label} must be a ModelJars schemaVersion 2 catalog`);
  }

  const ids = new Set();
  const coordinates = new Map();
  for (const model of document.models) {
    if (
      model === null ||
      typeof model !== "object" ||
      !/^[a-z0-9_]+$/.test(model.id ?? "")
    ) {
      throw new Error(`${label} contains an invalid model id`);
    }
    if (ids.has(model.id)) {
      throw new Error(`Duplicate catalog model id: ${model.id}`);
    }
    ids.add(model.id);

    const coordinate = parseCoordinate(model.markerCoordinate);
    const existingId = coordinates.get(model.markerCoordinate);
    if (existingId !== undefined) {
      throw new Error(
        `Duplicate markerCoordinate ${model.markerCoordinate}: ${existingId}, ${model.id}`,
      );
    }
    coordinates.set(model.markerCoordinate, model.id);
    modelCoordinateParts.set(model, coordinate);
  }
}

const modelCoordinateParts = new WeakMap();

function parseCoordinate(value) {
  if (typeof value !== "string") {
    throw new Error("markerCoordinate must be groupId:artifactId:version");
  }
  const parts = value.split(":");
  if (
    parts.length !== 3 ||
    !/^[A-Za-z0-9_.-]+$/.test(parts[0]) ||
    !/^[A-Za-z0-9_.-]+$/.test(parts[1]) ||
    !/^[A-Za-z0-9_.+-]+$/.test(parts[2])
  ) {
    throw new Error(
      `markerCoordinate must be groupId:artifactId:version: ${value}`,
    );
  }
  return { groupId: parts[0], artifactId: parts[1], version: parts[2] };
}

function canonicalJson(value) {
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(",")}]`;
  }
  if (value !== null && typeof value === "object") {
    return `{${Object.keys(value)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
      .join(",")}}`;
  }
  return JSON.stringify(value);
}

function assertProfiles(document, catalog, label) {
  if (
    document === null ||
    typeof document !== "object" ||
    document.schemaVersion !== 1 ||
    !Array.isArray(document.profiles)
  ) {
    throw new Error(`${label} must be a ModelJars performance profile catalog`);
  }
  const modelsById = new Map(catalog.models.map((model) => [model.id, model]));
  const ids = new Set();
  for (const profile of document.profiles) {
    if (
      profile === null ||
      typeof profile !== "object" ||
      typeof profile.id !== "string" ||
      typeof profile.modelId !== "string"
    ) {
      throw new Error(`${label} contains an invalid performance profile`);
    }
    if (ids.has(profile.id)) {
      throw new Error(`Duplicate performance profile id: ${profile.id}`);
    }
    ids.add(profile.id);
    const model = modelsById.get(profile.modelId);
    if (model === undefined) {
      throw new Error(
        `${label} profile ${profile.id} references unknown model ${profile.modelId}`,
      );
    }
    if (profile.markerCoordinate !== model.markerCoordinate) {
      throw new Error(
        `${label} profile ${profile.id} does not match ${model.markerCoordinate}`,
      );
    }
  }
}

function markerSnapshot(model, profileCatalog) {
  return {
    model,
    profiles: profileCatalog.profiles
      .filter((profile) => profile.modelId === model.id)
      .sort((left, right) => left.id.localeCompare(right.id)),
  };
}

export function publicationTaskName(id) {
  if (!/^[a-z0-9_]+$/.test(id)) {
    throw new Error(`Invalid catalog model id: ${id}`);
  }
  const suffix = id
    .split("_")
    .map((part) => part[0].toUpperCase() + part.slice(1))
    .join("");
  const publicationName = `marker${suffix}`;
  const publishTaskPrefix =
    `:modeljars-catalog:publish${publicationName[0].toUpperCase()}${publicationName.slice(1)}` +
    "PublicationTo";
  return {
    githubTaskName: `${publishTaskPrefix}GitHubPackagesRepository`,
    jarTaskName: `:modeljars-catalog:markerJar${suffix}`,
    publicationName,
    taskName: `${publishTaskPrefix}ReleaseBundleRepository`,
  };
}

export function catalogPublicationDelta(
  previousCatalog,
  currentCatalog,
  {
    previousProfiles = { schemaVersion: 1, profiles: [] },
    currentProfiles = { schemaVersion: 1, profiles: [] },
  } = {},
) {
  assertCatalog(previousCatalog, "Previous catalog");
  assertCatalog(currentCatalog, "Current catalog");
  assertProfiles(previousProfiles, previousCatalog, "Previous profiles");
  assertProfiles(currentProfiles, currentCatalog, "Current profiles");

  const previousById = new Map(
    previousCatalog.models.map((model) => [model.id, model]),
  );
  const currentById = new Map(
    currentCatalog.models.map((model) => [model.id, model]),
  );
  const previousCoordinates = new Map(
    previousCatalog.models.map((model) => [model.markerCoordinate, model.id]),
  );

  const publications = [];
  for (const current of currentCatalog.models) {
    const previous = previousById.get(current.id);
    if (previous === undefined) {
      const previousOwner = previousCoordinates.get(current.markerCoordinate);
      if (previousOwner !== undefined && previousOwner !== current.id) {
        throw new Error(
          `markerCoordinate ${current.markerCoordinate} was already owned by ${previousOwner}`,
        );
      }
      publications.push(publication(current, "added"));
      continue;
    }

    if (
      canonicalJson(markerSnapshot(previous, previousProfiles)) ===
      canonicalJson(markerSnapshot(current, currentProfiles))
    ) {
      continue;
    }
    if (previous.markerCoordinate === current.markerCoordinate) {
      throw new Error(
        `Catalog model ${current.id} changed without a new markerCoordinate`,
      );
    }

    const previousCoordinate = modelCoordinateParts.get(previous);
    const currentCoordinate = modelCoordinateParts.get(current);
    if (
      previousCoordinate.groupId !== currentCoordinate.groupId ||
      previousCoordinate.artifactId !== currentCoordinate.artifactId
    ) {
      throw new Error(
        `Catalog model ${current.id} must retain its marker groupId and artifactId`,
      );
    }
    const previousOwner = previousCoordinates.get(current.markerCoordinate);
    if (previousOwner !== undefined && previousOwner !== current.id) {
      throw new Error(
        `markerCoordinate ${current.markerCoordinate} was already owned by ${previousOwner}`,
      );
    }
    publications.push(publication(current, "updated"));
  }

  return {
    publications,
    removed: previousCatalog.models
      .filter((model) => !currentById.has(model.id))
      .map((model) => model.id)
      .sort(),
  };
}

export function selectCatalogPublications(catalog, ids) {
  assertCatalog(catalog, "Catalog");
  if (!Array.isArray(ids) || ids.length === 0) {
    throw new Error("At least one catalog model id is required");
  }
  const requestedIds = [...new Set(ids)];
  if (requestedIds.includes("all") && requestedIds.length !== 1) {
    throw new Error("all must be used by itself");
  }
  const modelsById = new Map(catalog.models.map((model) => [model.id, model]));
  const selectedModels =
    requestedIds[0] === "all"
      ? catalog.models
      : requestedIds.map((id) => {
          const model = modelsById.get(id);
          if (model === undefined) {
            throw new Error(`Unknown catalog model id: ${id}`);
          }
          return model;
        });
  const publications = selectedModels.map((model) =>
    publication(model, "selected"),
  );
  return { publications, removed: [] };
}

export function filterQualifiedPublications(delta, catalog, qualifications) {
  assertCatalog(catalog, "Catalog");
  if (
    qualifications === null ||
    typeof qualifications !== "object" ||
    qualifications.schemaVersion !== 1 ||
    !Array.isArray(qualifications.entries)
  ) {
    throw new Error("Qualifications must be a ModelJars schemaVersion 1 manifest");
  }

  const modelsById = new Map(catalog.models.map((model) => [model.id, model]));
  const qualifiedIds = new Set();
  const qualificationIds = new Set();
  for (const qualification of qualifications.entries) {
    const id = qualification?.modelId;
    if (typeof id !== "string" || qualificationIds.has(id)) {
      throw new Error(`Invalid or duplicate qualification model id: ${id}`);
    }
    qualificationIds.add(id);
    const model = modelsById.get(id);
    if (model === undefined) {
      throw new Error(`Qualification references unknown catalog model: ${id}`);
    }
    if (qualification.artifactSha256 !== model.sha256) {
      throw new Error(`Qualification SHA-256 does not match catalog model ${id}`);
    }
    if (qualification.artifactSizeBytes !== model.sizeBytes) {
      throw new Error(`Qualification size does not match catalog model ${id}`);
    }
    if (qualification.qualified === true) {
      qualifiedIds.add(id);
    }
  }

  return {
    ...delta,
    publications: (delta.publications || []).filter((publication) =>
      qualifiedIds.has(publication.id),
    ),
  };
}

function publication(model, change) {
  return {
    change,
    coordinate: model.markerCoordinate,
    id: model.id,
    ...publicationTaskName(model.id),
  };
}

export function githubPublicationOutputs(delta) {
  const publications = delta.publications ?? [];
  const removed = delta.removed ?? [];
  return {
    count: publications.length.toString(),
    hasPublications: publications.length === 0 ? "false" : "true",
    matrix: JSON.stringify({
      include: publications.map((publication) => {
        const coordinate = parseCoordinate(publication.coordinate);
        return {
          coordinate: publication.coordinate,
          githubTask: publication.githubTaskName,
          id: publication.id,
          jarPath:
            "modeljars-catalog/build/libs/markers/" +
            `${coordinate.artifactId}-${coordinate.version}.jar`,
          jarTask: publication.jarTaskName,
          releaseTask: publication.taskName,
        };
      }),
    }),
    removed: JSON.stringify(removed),
  };
}
