/**
 * The SaaS apps of Scan Configurations, in the console's order (design 6.8). `id` is the backend's
 * `SaasIntegration.provider`. Every icon id is a full string literal, so the offline icon bundle holds it.
 */
export interface SaasProviderDef {
  id: string;
  label: string;
  icon: string;
}

export const SAAS_PROVIDERS: ReadonlyArray<SaasProviderDef> = [
  { id: 'MICROSOFT_365', label: 'Microsoft 365', icon: 'mdi:microsoft-office' },
  { id: 'GOOGLE_WORKSPACE', label: 'Google Workspace', icon: 'mdi:google' },
  { id: 'JIRA', label: 'Jira', icon: 'mdi:jira' },
  // mdi has no Confluence glyph; Atlassian is its maker's mark (as DspmIcons).
  { id: 'CONFLUENCE', label: 'Confluence', icon: 'mdi:atlassian' },
  { id: 'GITLAB', label: 'GitLab', icon: 'mdi:gitlab' },
  { id: 'SLACK', label: 'Slack', icon: 'mdi:slack' },
];

/** The Slack drive type, whose Selected scope lists channels instead of users. */
export const SLACK_DRIVE = 'SLACK';
