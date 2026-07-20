#!/usr/bin/env node

// Build-time converter for the pinned Pi 0.80.6 generated model sources.
// The application consumes the generated Kotlin file and never executes Node.
import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";

const root = path.resolve("参考内容/Pi源码/pi-main/packages/ai/src/providers");
const output = path.resolve("pi-ai/src/commonMain/kotlin/dev/xuexue/pi/ai/PiGeneratedModelCatalog.kt");
const imageOutput = path.resolve("pi-ai/src/commonMain/kotlin/dev/xuexue/pi/ai/PiGeneratedImageModelCatalog.kt");

function loadModels(file) {

  let source = fs.readFileSync(file, "utf8");
  source = source.replace(/^import[^\n]*\n/gm, "");
  source = source.replace(/export const (\w+)\s*=\s*/g, "globalThis.$1 = ");
  source = source.replace(/\s+as const/g, "");
  source = source.replace(/}\s+satisfies\s+Record[^;]+;/g, "};");
  source = source.replace(/\s+satisfies\s+(?:Model|ImagesModel)<[^>]+>/g, "");
  const context = {};
  vm.runInNewContext(`${source}\n`, context, { filename: file });
  return Object.values(context).find((value) => value && typeof value === "object" && !Array.isArray(value)) ?? {};
}

function kotlinString(value) {
  return JSON.stringify(String(value));
}

function kotlinDouble(value) {
  const number = Number(value ?? 0);
  if (!Number.isFinite(number)) return "0.0";
  if (Number.isInteger(number)) return `${number}.0`;
  return String(number);
}

function kotlinLong(value) {
  return `${Math.trunc(Number(value ?? 0))}L`;
}

function mapThinking(value) {
  if (!value || typeof value !== "object") return null;
  const entries = Object.entries(value).map(([key, mapped]) => {
    const enumName = key.toUpperCase();
    const kotlinValue = mapped == null ? "null" : kotlinString(mapped);
    return `ModelThinkingLevel.${enumName} to ${kotlinValue}`;
  });
  return `mapOf(${entries.join(", ")})`;
}

function mapHeaders(value) {
  if (!value || typeof value !== "object") return null;
  const entries = Object.entries(value).map(([key, mapped]) => `${kotlinString(key)} to ${kotlinString(mapped)}`);
  return `mapOf(${entries.join(", ")})`;
}

function mapCompat(value) {
  if (!value || typeof value !== "object") return null;
  const json = JSON.stringify(value).replace(/\\/g, "\\\\").replace(/"/g, '\\"');
  return `PiJson.parseToJsonElement("${json}").jsonObject`;
}

function mapTiers(value) {
  if (!Array.isArray(value) || value.length === 0) return null;
  const entries = value.map((tier) =>
    `ModelCostTier(${kotlinDouble(tier.input)}, ${kotlinDouble(tier.output)}, ${kotlinDouble(tier.cacheRead)}, ${kotlinDouble(tier.cacheWrite)}, ${kotlinLong(tier.inputTokensAbove)})`,
  );
  return `listOf(${entries.join(", ")})`;
}

function modelLiteral(model) {
  const input = (model.input ?? []).filter((value) => value === "text" || value === "image");
  const fields = [
    `id = ${kotlinString(model.id)}`,
    `name = ${kotlinString(model.name ?? model.id)}`,
    `api = ${kotlinString(model.api ?? "openai-completions")}`,
    `provider = ${kotlinString(model.provider ?? "")}`,
    `baseUrl = ${kotlinString(model.baseUrl ?? "")}`,
    `reasoning = ${Boolean(model.reasoning)}`,
    `input = listOf(${input.map((value) => `ModelInput.${value.toUpperCase()}`).join(", ")})`,
    `cost = ModelCost(${kotlinDouble(model.cost?.input)}, ${kotlinDouble(model.cost?.output)}, ${kotlinDouble(model.cost?.cacheRead)}, ${kotlinDouble(model.cost?.cacheWrite)}${mapTiers(model.cost?.tiers) ? `, tiers = ${mapTiers(model.cost.tiers)}` : ""})`,
    `contextWindow = ${kotlinLong(model.contextWindow)}`,
    `maxTokens = ${kotlinLong(model.maxTokens)}`,
  ];
  const thinking = mapThinking(model.thinkingLevelMap);
  const headers = mapHeaders(model.headers);
  const compat = mapCompat(model.compat);
  if (thinking) fields.splice(6, 0, `thinkingLevelMap = ${thinking}`);
  if (headers) fields.push(`headers = ${headers}`);
  if (compat) fields.push(`compat = ${compat}`);
  return `        Model(\n            ${fields.join(",\n            ")},\n        )`;
}

function imageModelLiteral(model) {
  const input = (model.input ?? []).filter((value) => value === "text" || value === "image");
  const output = (model.output ?? []).filter((value) => value === "text" || value === "image");
  return `        ImagesModel(\n            id = ${kotlinString(model.id)},\n            name = ${kotlinString(model.name ?? model.id)},\n            api = ${kotlinString(model.api ?? "openrouter-images")},\n            provider = ${kotlinString(model.provider ?? "openrouter")},\n            baseUrl = ${kotlinString(model.baseUrl ?? "")},\n            input = listOf(${input.map((value) => `ModelInput.${value.toUpperCase()}`).join(", ")}),\n            output = listOf(${output.map((value) => `ModelInput.${value.toUpperCase()}`).join(", ")}),\n            cost = ModelCost(${kotlinDouble(model.cost?.input)}, ${kotlinDouble(model.cost?.output)}, ${kotlinDouble(model.cost?.cacheRead)}, ${kotlinDouble(model.cost?.cacheWrite)}),\n        )`;
}

const files = fs.readdirSync(root).filter((name) => name.endsWith(".models.ts")).sort();
const models = files.flatMap((name) => Object.values(loadModels(path.join(root, name))));
const unique = [];
const seen = new Set();
for (const model of models) {
  const key = `${model.provider}/${model.id}`;
  if (!model.id || seen.has(key)) continue;
  seen.add(key);
  unique.push(model);
}

const chunkSize = 40;
const chunks = [];
for (let index = 0; index < unique.length; index += chunkSize) {
  chunks.push(unique.slice(index, index + chunkSize));
}
const chunkFunctions = chunks
  .map((chunk, index) => `\nprivate fun generatedPiModels${index}(): List<Model> =\n    listOf(\n${chunk.map(modelLiteral).join(",\n")}\n    )\n`)
  .join("");
const text = `package dev.xuexue.pi.ai\n\nimport kotlinx.serialization.json.jsonObject\n\n/** Generated from the pinned Pi 0.80.6 provider model catalog. */\ninternal fun generatedPiModelCatalog(): List<Model> =\n    buildList {\n${chunks.map((_, index) => `        addAll(generatedPiModels${index}())`).join("\n")}\n    }\n${chunkFunctions}`;
fs.writeFileSync(
  output,
  text.replace(/^package dev\.xuexue\.pi\.ai\n/, '@file:Suppress("LongMethod", "MaxLineLength", "TooManyFunctions")\n\n$&'),
);
console.log(`Generated ${unique.length} models from ${files.length} provider catalogs: ${output}`);

const imageSource = path.resolve("参考内容/Pi源码/pi-main/packages/ai/src/image-models.generated.ts");
const imageRoot = loadModels(imageSource);
const imageModels = Object.values(imageRoot.openrouter ?? imageRoot);
const imageChunks = [];
for (let index = 0; index < imageModels.length; index += chunkSize) {
  imageChunks.push(imageModels.slice(index, index + chunkSize));
}
const imageFunctions = imageChunks
  .map((chunk, index) => `\nprivate fun generatedPiImageModels${index}(): List<ImagesModel> =\n    listOf(\n${chunk.map(imageModelLiteral).join(",\n")}\n    )\n`)
  .join("");
const imageText = `package dev.xuexue.pi.ai\n\n/** Generated from Pi 0.80.6 image-models.generated.ts. */\ninternal fun generatedPiImageModelCatalog(): List<ImagesModel> =\n    buildList {\n${imageChunks.map((_, index) => `        addAll(generatedPiImageModels${index}())`).join("\n")}\n    }\n${imageFunctions}`;
fs.writeFileSync(
  imageOutput,
  imageText.replace(/^package dev\.xuexue\.pi\.ai\n/, '@file:Suppress("LongMethod", "MaxLineLength", "TooManyFunctions")\n\n$&'),
);
console.log(`Generated ${imageModels.length} image models: ${imageOutput}`);
