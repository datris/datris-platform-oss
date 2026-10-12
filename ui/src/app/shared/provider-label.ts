/**
 * Display name for an AI provider id, shared by Configuration, the Assistant
 * model line and the tap wizard's generation line. Unknown ids pass through.
 */
const PROVIDER_DISPLAY_NAMES: Record<string, string> = {
  anthropic: 'Anthropic',
  openai: 'OpenAI',
  azure: 'Azure OpenAI',
  bedrock: 'Amazon Bedrock',
  grok: 'Grok (xAI)',
  ollama: 'Ollama'
};

export function providerDisplayName(p: string | null | undefined): string {
  if (!p) return '';
  return PROVIDER_DISPLAY_NAMES[p.toLowerCase()] ?? p;
}
