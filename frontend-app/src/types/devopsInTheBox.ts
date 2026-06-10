/**
 * TypeScript types for DevOps-in-the-Box functionality.
 *
 * These types model the enhanced DevOps script library (Phase 1 improvements):
 * a comprehensive, searchable, customizable catalog of production-ready scripts.
 *
 * Source of truth: design.md "Model 5: TypeScript Frontend Types" and
 * "Model 8: TypeScript Customization Types".
 */

// ─── Enums / String Literal Unions ─────────────────────────────────────────────

export type ScriptCategory = 'terraform' | 'bash' | 'python' | 'ansible';
export type CloudProvider = 'aws' | 'azure' | 'gcp' | 'multi-cloud';
export type DifficultyLevel = 'beginner' | 'intermediate' | 'advanced';
export type ScriptStatus = 'published' | 'draft' | 'deprecated';

// ─── Core Script Model ──────────────────────────────────────────────────────────

/**
 * Enhanced DevOpsScript type with full metadata, metrics, and status.
 */
export interface DevOpsScript {
  id: number;
  name: string;
  description: string;
  category: ScriptCategory;
  code: string;
  usageInstructions: string;
  version: string;

  // Enhanced fields
  tags: string[];
  cloudProviders: CloudProvider[];
  difficulty: DifficultyLevel;
  author: string;
  authorId: string;
  estimatedTimeMinutes: number;
  prerequisites: string;
  relatedDocumentationUrl: string;

  // Metrics
  viewCount: number;
  downloadCount: number;
  copyCount: number;
  favoriteCount: number;
  averageRating: number;
  ratingCount: number;

  // Timestamps (ISO-8601 strings)
  createdAt: string;
  updatedAt: string;
  lastUsedAt: string;

  // Status
  status: ScriptStatus;
  isUserContributed: boolean;
  isFeatured: boolean;

  // Client-side computed
  isFavorited?: boolean;
  userRating?: number;

  /**
   * @deprecated Legacy fields retained for backward compatibility with the
   * current DevOpsInTheBoxPage until the enhanced UI lands. Prefer
   * `downloadCount`, `updatedAt`, and `usageInstructions` respectively.
   */
  downloads?: number;
  /** @deprecated Use `updatedAt`. */
  lastUpdated?: string;
  /** @deprecated Use `usageInstructions`. */
  usage: string;
}

// ─── Filtering & Pagination ───────────────────────────────────────────────────

/**
 * Filter and sort state used by the Script Explorer.
 */
export interface ScriptFilters {
  searchQuery: string;
  categories: ScriptCategory[];
  cloudProviders: CloudProvider[];
  difficulties: DifficultyLevel[];
  tags: string[];
  minRating?: number;
  sortBy: 'recent' | 'popular' | 'rating' | 'name';
  showOnlyFavorites?: boolean;
}

/**
 * Pagination state for the script grid / infinite scroll.
 */
export interface PaginationState {
  page: number;
  pageSize: number;
  totalCount: number;
  hasMore: boolean;
}

/**
 * Generic paginated container returned by the list endpoint (GET /api/scripts).
 * The list endpoint returns a plain page of items; the API client wraps it with
 * pagination metadata for the UI / hooks layer.
 */
export interface PaginatedResponse<T> {
  items: T[];
  page: number;
  pageSize: number;
  totalCount: number;
  hasMore: boolean;
}

/**
 * Result of a full-text + filtered search (GET /api/scripts/search). Mirrors the
 * backend `SearchResult<T>` DTO: matched items in relevance/sort order together
 * with pagination metadata and query timing.
 */
export interface SearchResult<T> {
  items: T[];
  totalHits: number;
  page: number;
  size: number;
  totalPages: number;
  tookMillis: number;
}

/**
 * User interaction types recorded against a script. Mirrors the backend
 * interaction vocabulary (view, download, copy, favorite, unfavorite).
 */
export type InteractionType = 'view' | 'download' | 'copy' | 'favorite' | 'unfavorite';

/**
 * @deprecated Legacy filter shape used by the current DevOpsInTheBoxPage.
 * Replaced by {@link ScriptFilters}.
 */
export interface DevOpsFilters {
  searchTerm: string;
  filterCategory: 'all' | ScriptCategory;
}

// ─── Customization Types (design.md Model 8) ────────────────────────────────────

export interface ScriptParameter {
  id: number;
  name: string;
  label: string;
  description: string;
  parameterType: 'text' | 'number' | 'select' | 'multiselect' | 'boolean' | 'region' | 'instanceType';
  required: boolean;
  defaultValue?: any;
  validationRules?: {
    pattern?: string;
    min?: number;
    max?: number;
    options?: Array<{ value: string; label: string; description?: string }>;
  };
  category: 'infrastructure' | 'configuration' | 'security' | 'networking';
  dependsOn?: string;
  prefillFrom?: 'userProfile' | 'cloudAccount' | 'previousUsage';
  displayOrder: number;
}

export interface CustomizedScript {
  scriptId: number;
  scriptName: string;
  customizedCode: string;
  parameterValues: Record<string, any>;
  generatedAt: string;
}

export interface SavedCustomization {
  id: number;
  userId: string;
  scriptId: number;
  customizationName: string;
  parameterValues: Record<string, any>;
  generatedCode: string;
  isDefault: boolean;
  createdAt: string;
  updatedAt: string;
  lastUsedAt: string;
}

export interface ParameterValidationError {
  parameterName: string;
  errorMessage: string;
}
