#!/bin/bash

set -exuo pipefail

file=$1

grep misses $file | sed 's/--- //g' |  grep misses $file | sed 's/--- //g' | sed 's/ misses.*$//g' | paste -sd+ | bc

