#!/usr/bin/env python3
"""One-off migration: camelCase -> snake_case keys in MathVision persisted JSON.

Columns migrated:
  mathvision_artifacts.artifact_json        stage code_generation (CodeResult) and render_result (RenderResult)
  mathvision_stage_results.result_json      every stage (the executors' result objects)
  mathvision_versions.workflow_summary_json

Why not rename recursively: result_json embeds whole Narrative / KnowledgeGraph / geometry-report
documents (internal_checkpoints) whose keys are already snake_case, plus free-form payloads
(diagram_description, coordinate_model, style, ...). Only keys of known structures are renamed.

Rename tables (LEGACY_KEYS) are frozen from the @JsonAlias annotations that existed in
workflow/model/*.java before the data was migrated; the annotations themselves have been removed.

  migrate_mathvision_json_keys.py --dry-run     default: report what would change, write nothing
  migrate_mathvision_json_keys.py --apply       rewrite rows in batches (one transaction per batch)
  migrate_mathvision_json_keys.py --self-test   run offline tests, no database needed

Connection from MV_DB_HOST/PORT/USER/PASSWORD/NAME or flags. Idempotent. Take a backup first.
"""
import argparse
import json
import os
import re
import sys

CAMEL = re.compile(r"(?<=[a-z0-9])([A-Z])")


def snake(key):
    return CAMEL.sub(lambda m: "_" + m.group(1).lower(), key).lower()


# --- rename tables ------------------------------------------------------------

# Legacy camelCase -> snake_case key tables, one per persisted structure.
# Frozen from the @JsonAlias annotations that were removed from workflow/model/*.java once the data
# had been migrated; this file is now the only place that remembers the old names.
LEGACY_KEYS = {
    "CodeEvaluationResult": {
        "approvedForRender": "approved_for_render",
        "executionTimeSeconds": "execution_time_seconds",
        "finalReview": "final_review",
        "finalStaticAnalysis": "final_static_analysis",
        "gateReason": "gate_reason",
        "initialReview": "initial_review",
        "initialStaticAnalysis": "initial_static_analysis",
        "revisedCodeApplied": "revised_code_applied",
        "revisionAttempts": "revision_attempts",
        "revisionTriggered": "revision_triggered",
        "sceneName": "scene_name",
        "toolCalls": "tool_calls",
        "totalEvaluations": "total_evaluations",
    },
    "CodeFixResult": {
        "acceptanceIssues": "acceptance_issues",
        "acceptanceWarnings": "acceptance_warnings",
        "currentRequestPrompt": "current_request_prompt",
        "errorReason": "error_reason",
        "executionTimeSeconds": "execution_time_seconds",
        "failureReason": "failure_reason",
        "fixedContextPrompt": "fixed_context_prompt",
        "fixedGeneratedCode": "fixed_generated_code",
        "originalGeneratedCode": "original_generated_code",
        "postFixStaticAuditIssueCount": "post_fix_static_audit_issue_count",
        "postFixStaticAuditSummary": "post_fix_static_audit_summary",
        "rulesPrompt": "rules_prompt",
        "toolCalls": "tool_calls",
    },
    "CodeResult": {
        "artifactFormat": "artifact_format",
        "artifactName": "artifact_name",
        "executionTimeSeconds": "execution_time_seconds",
        "generatedCode": "generated_code",
        "headerCode": "header_code",
        "outputTarget": "output_target",
        "sceneEntries": "scene_entries",
        "sceneName": "scene_name",
        "targetConcept": "target_concept",
        "targetDescription": "target_description",
        "toolCalls": "tool_calls",
    },
    "ElementRef": {
        "className": "class_name",
        "sampleRole": "sample_role",
        "semanticName": "semantic_name",
        "stableId": "stable_id",
    },
    "EvaluationAttempt": {
        "approvedForRender": "approved_for_render",
        "gateReason": "gate_reason",
        "sceneName": "scene_name",
        "staticAnalysis": "static_analysis",
    },
    "LayoutIssue": {
        "intersectionArea": "intersection_area",
        "intersectionBounds": "intersection_bounds",
        "likelyFalsePositive": "likely_false_positive",
        "primaryElement": "primary_element",
        "reasonCode": "reason_code",
        "recommendedAction": "recommended_action",
        "secondaryElement": "secondary_element",
    },
    "RenderResult": {
        "artifactFileName": "artifact_file_name",
        "artifactMimeType": "artifact_mime_type",
        "artifactPath": "artifact_path",
        "artifactReceipt": "artifact_receipt",
        "artifactType": "artifact_type",
        "executionTimeSeconds": "execution_time_seconds",
        "finalGeneratedCode": "final_generated_code",
        "geometryPath": "geometry_path",
        "lastError": "last_error",
        "localArtifactPath": "local_artifact_path",
        "outputTarget": "output_target",
        "sceneName": "scene_name",
        "storageArtifactPath": "storage_artifact_path",
        "toolCalls": "tool_calls",
        "videoPath": "video_path",
    },
    "ReviewSnapshot": {
        "approvedForRender": "approved_for_render",
        "blockingIssues": "blocking_issues",
        "revisionDirectives": "revision_directives",
        "ruleChecks": "rule_checks",
    },
    "RuleCheck": {
        "ruleId": "rule_id",
    },
    "SampleEvaluation": {
        "blockingIssueCount": "blocking_issue_count",
        "elementCount": "element_count",
        "hasIssues": "has_issues",
        "issueCount": "issue_count",
        "offscreenIssueCount": "offscreen_issue_count",
        "overlapIssueCount": "overlap_issue_count",
        "playIndex": "play_index",
        "sampleId": "sample_id",
        "sampleRole": "sample_role",
        "sceneMethod": "scene_method",
        "sourceCode": "source_code",
    },
    "SceneEvaluationResult": {
        "blockingIssueCount": "blocking_issue_count",
        "executionTimeSeconds": "execution_time_seconds",
        "gateReason": "gate_reason",
        "geometryPath": "geometry_path",
        "issueSampleCount": "issue_sample_count",
        "offscreenIssueCount": "offscreen_issue_count",
        "overlapIssueCount": "overlap_issue_count",
        "renderSuccess": "render_success",
        "revisionAttempts": "revision_attempts",
        "revisionTriggered": "revision_triggered",
        "sampleCount": "sample_count",
        "sceneName": "scene_name",
        "toolCalls": "tool_calls",
        "totalIssueCount": "total_issue_count",
    },
    "StaticAnalysis": {
        "arrangeCount": "arrange_count",
        "className": "class_name",
        "codeLines": "code_lines",
        "fadeInCount": "fade_in_count",
        "fadeOutCount": "fade_out_count",
        "fadeTransformCount": "fade_transform_count",
        "hasConstruct": "has_construct",
        "largeShiftCount": "large_shift_count",
        "mathTexCount": "math_tex_count",
        "maxEnteringObjects": "max_entering_objects",
        "maxNarrationWordsPerSecond": "max_narration_words_per_second",
        "maxVisibleObjects": "max_visible_objects",
        "maxVisibleTextualObjects": "max_visible_textual_objects",
        "minNarrationWordsPerSecond": "min_narration_words_per_second",
        "nextToCount": "next_to_count",
        "replacementTransformCount": "replacement_transform_count",
        "sceneCount": "scene_count",
        "shiftCount": "shift_count",
        "textCount": "text_count",
        "threeDObjectCount": "three_dobject_count",
        "threeDScene": "three_dscene",
        "threeDStoryboardSceneCount": "three_dstoryboard_scene_count",
        "toEdgeCount": "to_edge_count",
        "transformCount": "transform_count",
        "usesManimImport": "uses_manim_import",
    },
    "StaticFinding": {
        "ruleId": "rule_id",
    },
    "StoryboardValidationReport": {
        "finalIssueCount": "final_issue_count",
        "finalIssues": "final_issues",
        "fixApplied": "fix_applied",
        "fixAttempted": "fix_attempted",
        "initialIssueCount": "initial_issue_count",
        "initialIssues": "initial_issues",
        "outputTarget": "output_target",
        "resolvedIssueCount": "resolved_issue_count",
        "sceneCount": "scene_count",
        "totalValidationEvents": "total_validation_events",
    },
    "StoryboardValidationTraceEntry": {
        "cleanupAttempt": "cleanup_attempt",
        "executionTimeSeconds": "execution_time_seconds",
        "fixApplied": "fix_applied",
        "fixAttempted": "fix_attempted",
        "issueCount": "issue_count",
        "sceneCount": "scene_count",
        "toolCalls": "tool_calls",
    },
}


def load_model_aliases():
    return LEGACY_KEYS


# Nested structure: class -> {key (legacy OR current spelling) -> child class | ("list", child class)}
NESTING = {
    "CodeResult": {"sceneEntries": ("list", "SceneCodeEntry"), "scene_entries": ("list", "SceneCodeEntry")},
    "CodeEvaluationResult": {
        "initialStaticAnalysis": "StaticAnalysis", "initial_static_analysis": "StaticAnalysis",
        "finalStaticAnalysis": "StaticAnalysis", "final_static_analysis": "StaticAnalysis",
        "initialReview": "ReviewSnapshot", "initial_review": "ReviewSnapshot",
        "finalReview": "ReviewSnapshot", "final_review": "ReviewSnapshot",
        "attempts": ("list", "EvaluationAttempt"),
    },
    "EvaluationAttempt": {
        "staticAnalysis": "StaticAnalysis", "static_analysis": "StaticAnalysis", "review": "ReviewSnapshot",
    },
    "StaticAnalysis": {"findings": ("list", "StaticFinding")},
    "ReviewSnapshot": {"ruleChecks": ("list", "RuleCheck"), "rule_checks": ("list", "RuleCheck")},
    "SceneEvaluationResult": {"samples": ("list", "SampleEvaluation")},
    "SampleEvaluation": {"issues": ("list", "LayoutIssue")},
    "LayoutIssue": {
        "primaryElement": "ElementRef", "primary_element": "ElementRef",
        "secondaryElement": "ElementRef", "secondary_element": "ElementRef",
    },
    "StoryboardValidationReport": {"entries": ("list", "StoryboardValidationTraceEntry")},
}

# result_json top level: key (legacy camelCase) is renamed generically; these children hold typed structures.
RESULT_CHILDREN = {
    "codeEvaluation": "CodeEvaluationResult", "code_evaluation": "CodeEvaluationResult",
    "codeEvaluationAttempts": ("list", "CodeEvaluationResult"),
    "code_evaluation_attempts": ("list", "CodeEvaluationResult"),
    "codeFixTrace": ("list", "CodeFixResult"), "code_fix_trace": ("list", "CodeFixResult"),
    "renderResult": "RenderResult", "render_result": "RenderResult",
    "lastRenderResult": "RenderResult", "last_render_result": "RenderResult",
    "sceneEvaluation": "SceneEvaluationResult", "scene_evaluation": "SceneEvaluationResult",
    "validationReport": "StoryboardValidationReport", "validation_report": "StoryboardValidationReport",
}
# internal_checkpoints holds whole documents (Narrative, KnowledgeGraph, geometry report) as VALUES.
# Only the checkpoint names themselves are renamed; the documents inside are never touched.
CHECKPOINT_NAMES = {
    "explorationGraph": "exploration_graph", "enrichedGraph": "enriched_graph",
    "visualDesign": "visual_design", "validatedStoryboard": "validated_storyboard",
    "geometryReport": "geometry_report",
}
OPAQUE_TOP_LEVEL = {"internalCheckpoints", "internal_checkpoints"}


class Migrator:
    def __init__(self, aliases):
        self.aliases = aliases
        self.changes = {}

    def _count(self, where, old):
        self.changes[(where, old)] = self.changes.get((where, old), 0) + 1

    def typed(self, value, cls, where):
        """Rename the keys of a known structure; recurse only along declared nesting."""
        if isinstance(value, list):
            return [self.typed(v, cls, where) for v in value]
        if not isinstance(value, dict):
            return value
        table = self.aliases.get(cls, {})
        nested = NESTING.get(cls, {})
        out = {}
        for key, child in value.items():
            new = table.get(key, key)
            if new != key:
                self._count(cls, key)
            spec = nested.get(key) or nested.get(new)
            if spec:
                kind_cls = spec[1] if isinstance(spec, tuple) else spec
                child = self.typed(child, kind_cls, where)
            if new in out:          # snake_case already present: keep it, drop the legacy duplicate
                continue
            out[new] = child
        return out

    def artifact(self, stage, doc):
        if stage == "code_generation":
            return self.typed(doc, "CodeResult", stage)
        if stage == "render_result":
            return self.typed(doc, "RenderResult", stage)
        return doc   # problem/graph/storyboard artifacts were always snake_case

    def result(self, doc):
        if not isinstance(doc, dict):
            return doc
        out = {}
        for key, value in doc.items():
            new = snake(key)
            if new != key:
                self._count("result_json", key)
            if key in OPAQUE_TOP_LEVEL or new == "internal_checkpoints":
                if isinstance(value, dict):
                    value = self._checkpoints(value)
            elif key in RESULT_CHILDREN:
                spec = RESULT_CHILDREN[key]
                value = self.typed(value, spec[1] if isinstance(spec, tuple) else spec, "result_json")
            elif key in ("qualityReview", "quality_review") and isinstance(value, dict):
                value = {snake(k): v for k, v in value.items()}
            if new in out and new != key:
                continue
            out[new] = value
        return out

    def _checkpoints(self, checkpoints):
        out = {}
        for name, document in checkpoints.items():
            new = CHECKPOINT_NAMES.get(name, name)
            if new != name:
                self._count("internal_checkpoints", name)
            out.setdefault(new, document)
        return out

    def summary(self, doc):
        if not isinstance(doc, dict):
            return doc
        out = {}
        for key, value in doc.items():
            new = snake(key)
            if new != key:
                self._count("workflow_summary_json", key)
            if new == "stages" and isinstance(value, dict):
                value = {stage: self._summary_stage(stage_doc) for stage, stage_doc in value.items()}
            elif new == "execution_commits":
                pass                  # keys are lock tokens (UUIDs): must stay verbatim
            if new in out and new != key:
                continue
            out[new] = value
        return out

    def _summary_stage(self, doc):
        if not isinstance(doc, dict):
            return doc
        out = {}
        for key, value in doc.items():
            new = snake(key)
            if new != key:
                self._count("workflow_summary_json", key)
            if new == "result":
                value = self.result(value)
            out.setdefault(new, value)
        return out


# --- database ----------------------------------------------------------------------------------------------

def connect(args):
    import pymysql
    return pymysql.connect(host=args.host, port=args.port, user=args.user, password=args.password,
                           database=args.database, charset="utf8mb4", autocommit=False)


def migrate_table(conn, migrator, table, id_col, json_col, extra_col, transform, apply, batch):
    changed = scanned = 0
    last_id = 0
    cur = conn.cursor()
    while True:
        cols = f"{id_col}, {json_col}" + (f", {extra_col}" if extra_col else "")
        cur.execute(f"SELECT {cols} FROM {table} WHERE {id_col} > %s AND {json_col} IS NOT NULL "
                    f"ORDER BY {id_col} LIMIT %s", (last_id, batch))
        rows = cur.fetchall()
        if not rows:
            break
        updates = []
        for row in rows:
            row_id, raw = row[0], row[1]
            last_id = row_id
            scanned += 1
            try:
                doc = json.loads(raw) if isinstance(raw, (str, bytes)) else raw
            except ValueError:
                print(f"  ! {table}.{row_id}: invalid JSON, skipped", file=sys.stderr)
                continue
            new_doc = transform(doc, row[2] if extra_col else None)
            if type(new_doc) is not type(doc):
                raise SystemExit(f"{table}.{row_id}: transform changed the JSON type "
                                 f"({type(doc).__name__} -> {type(new_doc).__name__}); aborting, nothing more written")
            if new_doc != doc:
                changed += 1
                updates.append((json.dumps(new_doc, ensure_ascii=False), row_id))
        if apply and updates:
            # update_time must not move: these tables use ON UPDATE CURRENT_TIMESTAMP
            cur.executemany(f"UPDATE {table} SET {json_col} = %s, update_time = update_time "
                            f"WHERE {id_col} = %s", updates)
            conn.commit()
    return scanned, changed


def verify_clean(conn):
    """After --apply: no legacy camelCase top-level keys may remain in the migrated documents."""
    cur = conn.cursor()
    problems = []
    probes = [
        ("mathvision_artifacts", "artifact_json", "stage = 'code_generation'", "$.generatedCode"),
        ("mathvision_artifacts", "artifact_json", "stage = 'render_result'", "$.artifactPath"),
        ("mathvision_stage_results", "result_json", "1=1", "$.apiCalls"),
        ("mathvision_stage_results", "result_json", "1=1", "$.qualityReview"),
        ("mathvision_versions", "workflow_summary_json", "1=1", "$.totalApiCalls"),
        ("mathvision_versions", "workflow_summary_json", "1=1", "$.executionCommits"),
    ]
    for table, col, where, path in probes:
        cur.execute(f"SELECT COUNT(*) FROM {table} WHERE {where} AND JSON_CONTAINS_PATH({col}, 'one', %s)", (path,))
        n = cur.fetchone()[0]
        if n:
            problems.append(f"{table}.{col} still has {n} rows with {path}")
    return problems


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--apply", action="store_true", help="write changes (default is a dry run)")
    mode.add_argument("--dry-run", action="store_true", help="report only (default)")
    mode.add_argument("--self-test", action="store_true", help="run offline tests and exit")
    ap.add_argument("--batch", type=int, default=200)
    ap.add_argument("--host", default=os.getenv("MV_DB_HOST", "127.0.0.1"))
    ap.add_argument("--port", type=int, default=int(os.getenv("MV_DB_PORT", "3306")))
    ap.add_argument("--user", default=os.getenv("MV_DB_USER"))
    ap.add_argument("--password", default=os.getenv("MV_DB_PASSWORD"))
    ap.add_argument("--database", default=os.getenv("MV_DB_NAME"))
    args = ap.parse_args()

    if args.self_test:
        return self_test()
    if not (args.user and args.database):
        ap.error("database user and name are required (MV_DB_USER / MV_DB_NAME or flags)")

    aliases = load_model_aliases()
    migrator = Migrator(aliases)
    conn = connect(args)
    try:
        print("mode:", "APPLY" if args.apply else "DRY-RUN (nothing is written)")
        jobs = [
            ("mathvision_artifacts", "id", "artifact_json", "stage", lambda d, stage: migrator.artifact(stage, d)),
            ("mathvision_stage_results", "id", "result_json", None, lambda d, _: migrator.result(d)),
            ("mathvision_versions", "id", "workflow_summary_json", None, lambda d, _: migrator.summary(d)),
        ]
        for table, id_col, col, extra, fn in jobs:
            scanned, changed = migrate_table(conn, migrator, table, id_col, col, extra, fn, args.apply, args.batch)
            print(f"{table}.{col}: scanned {scanned}, {'rewrote' if args.apply else 'would rewrite'} {changed}")
        print("renamed keys (structure, legacy key -> count):")
        for (where, old), n in sorted(migrator.changes.items()):
            print(f"  {where:28s} {old:34s} -> {snake(old):38s} x{n}")
        if args.apply:
            problems = verify_clean(conn)
            if problems:
                print("VERIFY FAILED:", *problems, sep="\n  ")
                return 1
            print("verify: no legacy top-level keys remain")
        return 0
    finally:
        conn.close()


# --- offline self-test --------------------------------------------------------------------------------------

def self_test():
    aliases = load_model_aliases()
    m = Migrator(aliases)
    failures = []

    def check(name, got, want):
        if got != want:
            failures.append(f"{name}\n   got  {got}\n   want {want}")

    check("legacy table: CodeResult", aliases["CodeResult"]["generatedCode"], "generated_code")
    check("nested class alias", aliases["StaticAnalysis"]["fadeInCount"], "fade_in_count")
    check("nested class alias 2", aliases["RuleCheck"]["ruleId"], "rule_id")

    # artifacts
    code = {"generatedCode": "x", "sceneName": "S", "sceneEntries": [{"scene_id": "a", "scene_code": "c"}],
            "description": "d", "toolCalls": 3}
    check("CodeResult", m.artifact("code_generation", code),
          {"generated_code": "x", "scene_name": "S", "scene_entries": [{"scene_id": "a", "scene_code": "c"}],
           "description": "d", "tool_calls": 3})
    check("RenderResult", m.artifact("render_result", {"success": True, "artifactPath": "/a.mp4", "attempts": 2}),
          {"success": True, "artifact_path": "/a.mp4", "attempts": 2})
    check("other artifacts untouched", m.artifact("visual_storyboard", {"object_registry": [{"id": "A"}]}),
          {"object_registry": [{"id": "A"}]})

    # result_json: generic top level + typed children + opaque checkpoints
    narrative = {"scenes": [{"scene_id": "s1", "entering_objects": [{"id": "A", "style": {"fill_color": "#fff"}}]}]}
    result = {
        "apiCalls": 4, "internalCheckpoints": {"visualDesign": narrative, "geometryReport": {"frame_bounds": {}}},
        "qualityReview": {"supported": True, "status": "pending", "node": "X"},
        "codeEvaluation": {"approvedForRender": False, "finalReview": {"blockingIssues": ["b"],
                           "ruleChecks": [{"ruleId": "r", "status": "fail"}]},
                           "finalStaticAnalysis": {"fadeInCount": 2, "findings": [{"ruleId": "q", "summary": "s"}]}},
        "renderResult": {"success": True, "artifactPath": "/a", "lastError": "e"},
        "sceneEvaluation": {"gateReason": "g", "samples": [{"sampleId": "x", "issues": [
            {"reasonCode": "r", "primaryElement": {"stableId": "id", "bounds": {"min": [0, 0]}}}]}]},
        "codeFixTrace": [{"fixedGeneratedCode": "f", "outcome": "FIXED"}],
        "validationReport": {"outputTarget": "manim", "sceneCount": 3, "entries": [{"cleanupAttempt": 1}]},
    }
    got = m.result(json.loads(json.dumps(result)))
    check("result top-level keys", sorted(got), sorted([
        "api_calls", "internal_checkpoints", "quality_review", "code_evaluation", "render_result",
        "scene_evaluation", "code_fix_trace", "validation_report"]))
    check("checkpoint names renamed, documents untouched", got["internal_checkpoints"],
          {"visual_design": narrative, "geometry_report": {"frame_bounds": {}}})
    check("already-migrated checkpoints are stable", m.result({"internal_checkpoints": got["internal_checkpoints"]})
          ["internal_checkpoints"], got["internal_checkpoints"])
    check("quality review", got["quality_review"], result["qualityReview"])
    ce = got["code_evaluation"]
    check("typed nested review", ce["final_review"], {"blocking_issues": ["b"],
          "rule_checks": [{"rule_id": "r", "status": "fail"}]})
    check("typed nested static analysis", ce["final_static_analysis"],
          {"fade_in_count": 2, "findings": [{"rule_id": "q", "summary": "s"}]})
    check("scene evaluation deep", got["scene_evaluation"]["samples"][0]["issues"][0]["primary_element"],
          {"stable_id": "id", "bounds": {"min": [0, 0]}})
    check("fix trace", got["code_fix_trace"], [{"fixed_generated_code": "f", "outcome": "FIXED"}])
    check("validation report", got["validation_report"],
          {"output_target": "manim", "scene_count": 3, "entries": [{"cleanup_attempt": 1}]})

    # idempotent and mixed rows
    again = m.result(json.loads(json.dumps(got)))
    check("idempotent", again, got)
    check("snake wins over stale camel duplicate", m.result({"apiCalls": 1, "api_calls": 9}), {"api_calls": 9})
    check("hasConstruct boolean alias", m.typed({"hasConstruct": True, "usesManimImport": False},
                                                 "StaticAnalysis", "t"),
          {"has_construct": True, "uses_manim_import": False})

    # workflow summary keeps lock-token keys verbatim and converts nested stage results
    summary = {"taskId": 1, "totalApiCalls": 7, "executionCommits": {"7a1fAbC-9d": "render_result"},
               "stages": {"render_result": {"artifactId": 5, "changeSource": "x",
                                            "result": {"apiCalls": 3, "renderResult": {"artifactPath": "/p"}}}}}
    s = m.summary(summary)
    check("summary keys", sorted(s), ["execution_commits", "stages", "task_id", "total_api_calls"])
    check("lock token preserved", s["execution_commits"], {"7a1fAbC-9d": "render_result"})
    check("summary nested stage", s["stages"]["render_result"],
          {"artifact_id": 5, "change_source": "x",
           "result": {"api_calls": 3, "render_result": {"artifact_path": "/p"}}})

    # every legacy name the Java models accept must be covered by the generated tables
    for cls, table in aliases.items():
        for legacy, current in table.items():
            if snake(legacy) != current:
                failures.append(f"alias {cls}.{legacy} -> {current} is not plain camel->snake")
    class FakeCursor:
        def __init__(self, store):
            self.store, self.rows, self.written = store, [], {}
        def execute(self, sql, params=()):
            if sql.startswith("SELECT"):
                after = params[0]
                self.rows = [r for r in self.store if r[0] > after][:params[1]]
        def fetchall(self):
            rows, self.rows = self.rows, []
            return rows
        def executemany(self, sql, seq):
            for text, row_id in seq:
                self.written[row_id] = json.loads(text)
    class FakeConn:
        def __init__(self, store):
            self.cur = FakeCursor(store)
        def cursor(self):
            return self.cur
        def commit(self):
            pass
    store = [(1, json.dumps({"generatedCode": "x"}), "code_generation"),
             (2, json.dumps({"artifactPath": "/a"}), "render_result"),
             (3, json.dumps({"scenes": [{"scene_id": "s"}]}), "visual_storyboard")]
    conn = FakeConn(store)
    scanned, changed = migrate_table(conn, Migrator(aliases), "mathvision_artifacts", "id", "artifact_json", "stage",
                                     lambda d, stage: Migrator(aliases).artifact(stage, d), True, 10)
    check("artifact rows scanned/changed (storyboard row must NOT change)", (scanned, changed), (3, 2))
    check("artifact rows written", conn.cur.written, {1: {"generated_code": "x"}, 2: {"artifact_path": "/a"}})

    if failures:
        print("SELF-TEST FAILED")
        print("\n".join(failures))
        return 1
    print(f"self-test passed ({len(aliases)} model classes, {sum(len(t) for t in aliases.values())} aliases)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
