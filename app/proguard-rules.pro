# App-specific R8 rules.
# The app parses JSON manually with org.json and does not rely on reflection for its own models,
# so the default optimized Android rules are enough for now. Keep this file for future library
# keep rules if Play/R8 surfaces any release-only issue.
