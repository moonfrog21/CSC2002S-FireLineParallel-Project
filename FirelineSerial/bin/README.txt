FIRELINE SERIAL PROTOTYPE
=========================

This is a Java 11-compatible serial prototype for a possible CSC2002S PCP1
parallel-programming assignment.

The core simulation is a deterministic four-neighbour stencil calculation.
It uses double buffering: each timestep reads from the current arrays and
writes to separate next-state arrays, then swaps the arrays.

FILES
-----
FirelineSerial.java  Main program and command-line validation
FireMap.java         Landscape, simulation state, update loop and PNG output
TerrainType.java     Fixed terrain parameters
Makefile             Build and run commands

BUILD AND RUN
-------------
make run

The default command is equivalent to:

java -cp bin FirelineSerial 300 300 42 wildfire output/fireline

Arguments:
  rows
  columns
  random seed
  mode: diffusion or wildfire
  output file prefix
  optional maximum timestep count
  optional convergence tolerance
  optional landscape: mixed or grass (default: mixed)
  optional ignition patch: top row, left column, patch size

The three ignition-patch arguments must be supplied together. The top row and
left column identify the top-left corner of a square patch. The complete patch
must lie inside the fixed boundary cells.

Mixed-landscape example:

make run ARGS="500 500 17 wildfire output/run17 5000 0.05 mixed"

Grass-only benchmark example with a 9 x 9 ignition patch at (20,20):

make run ARGS="2000 2000 17 wildfire output/grass2000 50000 0.05 grass 20 20 9"

If the grass landscape is selected without explicit patch arguments, the
program uses a 9 x 9 patch near the top-left corner, normally at (20,20).
The random seed is still accepted for command compatibility but does not alter
the grass-only landscape.

OUTPUT IMAGES
-------------
<prefix>_terrain.png  Fixed terrain layout
<prefix>_peak.png     Maximum temperature reached by every cell
<prefix>_final.png    Final terrain and burn scar state

NOTES
-----
The model is deliberately simplified and deterministic. It is an educational
stencil computation, not a predictive wildfire model.

The simulation time printed by the program excludes PNG creation.
