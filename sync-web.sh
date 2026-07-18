#!/bin/sh
# Rigenera www/index.html dall'unica fonte ../lettore.html
# (stessa trasformazione usata per il sito GitHub Pages)
set -e
cd "$(dirname "$0")"
{
  printf '<!doctype html>\n<html lang="it">\n'
  cat ../lettore.html
  printf '</html>\n'
} > www/index.html
echo "www/index.html rigenerato ($(wc -c < www/index.html) byte)"
