-- Total Cost for Selected Recipes (ProductionService.totalCostForRecipes) sums the production runs
-- costed from each recipe: costings by recipe, then runs by costing. Both foreign keys had no index
-- leading with them. Concurrent, like V13.
create index concurrently if not exists production_costings_recipe_idx on production_costings (tenant_id, recipe_id) where recipe_id is not null;
create index concurrently if not exists production_runs_costing_idx on production_runs (production_costing_id);
