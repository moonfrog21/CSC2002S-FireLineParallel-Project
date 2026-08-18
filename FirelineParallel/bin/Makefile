JAVAC = javac
JAVA = java
JFLAGS = --release 11 -d bin
SOURCES = FirelineParallel.java FireMap.java TerrainType.java
ARGS ?= 300 300 42 wildfire output/fireline

.PHONY: all run clean

all:
	mkdir -p bin
	$(JAVAC) $(JFLAGS) $(SOURCES)

run: all
	$(JAVA) -cp bin FirelineParallel $(ARGS)

clean:
	rm -rf bin output
