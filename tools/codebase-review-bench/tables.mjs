// Markdown tables from JfrSummary.java's text output (B1, PROFILE_REVIEW.md).
// node tools/codebase-review-bench/tables.mjs <summary.txt> [section ...]
// Sections: categories, classes, methods, sites, siteclass, allocclass. Default: classes sites.
import { readFileSync } from "node:fs";

const [file, ...wanted] = process.argv.slice(2);
const text = readFileSync(file, "utf8").split(String.fromCharCode(13)).join("");
const titles = {
  categories: "Time-share categories",
  classes: "Top classes by self samples",
  methods: "Top methods by self samples",
  sites: "Allocation by first mod frame",
  siteclass: "Allocation by site and class",
  allocclass: "Allocation by class",
};
function section(title) {
  const start = text.indexOf(`## ${title}\n`);
  if (start < 0) return [];
  const rest = text.slice(start + title.length + 4);
  const end = rest.indexOf("\n## ");
  return (end < 0 ? rest : rest.slice(0, end)).split("\n").filter((l) => l.trim());
}
const short = (s) => s.replace(/^com\.wormzjl\.icheme\./, "").replace(/^org\.ejml\./, "ejml.");
for (const key of wanted.length ? wanted : ["classes", "sites"]) {
  const rows = section(titles[key]);
  console.log(`\n${titles[key]}\n`);
  const mb = key === "sites" || key === "siteclass" || key === "allocclass";
  console.log(mb ? "| # | Share | MB | Site |\n|---|---|---|---|" : "| # | Share | Samples | Frame |\n|---|---|---|---|");
  rows.forEach((line, i) => {
    const m = mb ? line.match(/^\s*([\d.]+) %\s+([\d.]+) MB\s+(.*)$/) : line.match(/^\s*([\d.]+) %\s+(\d+)\s+(.*)$/);
    if (m) console.log(`| ${i + 1} | ${m[1]} % | ${m[2]} | \`${short(m[3]).replace(/\|/g, "\\|")}\` |`);
  });
}
