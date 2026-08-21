import type { App, MarkdownView, TFile, WorkspaceLeaf } from "obsidian";
import { byActiveTime } from "./leaf-open";

export type EditableMarkdownLeaf = WorkspaceLeaf & {
  view: MarkdownView & { file: TFile };
};

/** Return the most recently focused editable Markdown tab. */
export function getMostRecentEditorLeaf(
  app: App,
): EditableMarkdownLeaf | null {
  const leaf = app.workspace
    .getLeavesOfType("markdown")
    .filter((candidate) => {
      const view = candidate.view as MarkdownView;
      return view.file && view.getMode() === "source";
    })
    .sort(byActiveTime)[0];

  return (leaf as EditableMarkdownLeaf | undefined) ?? null;
}
