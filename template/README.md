# Vendor Integration Template

This directory contains scaffolding for building a new equipment vendor adapter:

- `starter/`: required interfaces (`VendorPlugin`, `MachineProfile`, `CommandEncoder`, `TelemetryDecoder`) and a registry scaffold.
- `examples/demogym/`: complete DemoGym adapter plus simulator hooks.
- `assets/`: branded asset placeholders.
- `config/`: starter vendor config file.

Use `scripts/new_vendor_template.sh` (or `.ps1`) to clone the starter package, rename package IDs, and register your plugin class.
