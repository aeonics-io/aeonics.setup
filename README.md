## Plugin: "aeonics.setup"

This *Nucleus Runtime* plugin provides a default implementation of system 
managers and initial lifecycle.

## Compile and package

You can use your favourite tool (Maven, Gradle,...) but to be honest, we prefer
the plain simple standard and out-of-the-box `javac`.

The binary distribution of the *aeonics.boot* jar should be in the
current directory, and the *aeonics.core* jar should be in the `plugins` 
directory.

```shell
javac -source 11 -target 11 -nowarn -XDignore.symbol.file \
      -d aeonics.setup/bin \
      --module-path .;plugins \
      --module-source-path .\
      --module aeonics.setup

jar -c --file=aeonics.setup.jar \
    -C aeonics.setup/bin/aeonics.setup \
    .
```

## Deployment

Place the binary distribution in the `plugins` folder of your installation.
