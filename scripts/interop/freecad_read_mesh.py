# Reads the STL / 3MF named by the MESH_FILE environment variable with FreeCAD's Mesh module and prints
# "Mesh volume <v> facets <n> solid <True|False>" (ADR-0049's external counter-check). Run with freecadcmd.
import os
import Mesh

path = os.environ["MESH_FILE"]
mesh = Mesh.Mesh(path)
print("Mesh volume %.6f facets %d solid %s" % (mesh.Volume, mesh.CountFacets, mesh.isSolid()))
