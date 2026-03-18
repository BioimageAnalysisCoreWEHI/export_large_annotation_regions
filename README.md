# export_large_annotation_regions

Nextflow pipeline for exporting large annotation regions from a QuPath project using headless QuPath and a Groovy script.

## What it does

- Runs QuPath in CLI mode against a `.qpproj` project.
- Executes `export_large_annotation_region.groovy` to export selected annotation ROIs.
- Collects exported OME-TIFF outputs from `ExportedAnnotations/`.

## Required parameters

- `--project` Path to QuPath project (`.qpproj`).
- `--qupath_bin` Path to QuPath executable (for example `/vast/projects/project_name/QuPath/bin/QuPath`).

Optional:

- `--script` Groovy script path (default: `bin/export_large_annotation_region.groovy`).
	Relative paths are resolved from the pipeline directory.
- `--target_annotation_names` Comma-separated annotation names (default: `annotation_1`).
	Use an empty string (`""`) to export all annotations.
- `--downsample` Export downsample factor (default: `1.0`).
- `--compression_type` OME-TIFF compression type for QuPath writer (default: `LZW`).
- `--output_subdir` QuPath project-side output subdirectory name (default: `ExportedAnnotations`).
- `--tile_size` OME writer tile width/height in pixels (default: `512`).
- `--n_threads` Threads used by QuPath writer parallelization (default: `48`).
- `--big_tiff` Enable BigTIFF output for large files (default: `true`).
- `--build_pyramid` Enable pyramidal OME-TIFF output (default: `true`).
- `--outdir` Output directory for published results (default: `results`).
- `--publish_dir_mode` Nextflow `publishDir` mode (default: `copy`).

## Usage

Run on HPC with Slurm resources matching your current script (48 CPUs, 256 GB RAM):

```bash
nextflow run main.nf \
	-profile large \
	--project /vast/projects/project_name/Qupath_project/project.qpproj \
	--qupath_bin /vast/projects/project_name/QuPath/bin/QuPath \
	--script bin/export_large_annotation_region.groovy \
	--target_annotation_names annotation_1,annotation_2 \
	--downsample 1.0 \
	--compression_type LZW \
	--output_subdir ExportedAnnotations \
	--tile_size 512 \
	--n_threads 48 \
	--big_tiff true \
	--build_pyramid true \
	--outdir /path/to/output
```

## Outputs

- `ExportedAnnotations/` containing exported `.ome.tif` region files.
- `qupath_large_annotation_export.log` with full QuPath run logs.

## Notes

- Groovy export settings are now pipeline parameters, so you can change them via CLI without editing the script.
- If your environment does not provide `module load java/17`, use a QuPath build that bundles Java or enable the `conda` profile in `nextflow.config`.

