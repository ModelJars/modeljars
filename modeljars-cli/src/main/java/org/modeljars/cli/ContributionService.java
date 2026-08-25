package org.modeljars.cli;

import java.io.IOException;
@FunctionalInterface
interface ContributionService {
  ContributionDraft prepare(ContributionRequest request) throws IOException, InterruptedException;
}
