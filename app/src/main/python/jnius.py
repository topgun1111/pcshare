"""Chaquopy shim so lanshare.py's `from jnius import autoclass` works unchanged."""
from java import jclass


def autoclass(name):
    return jclass(name)
