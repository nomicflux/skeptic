#!/bin/bash

VERSION=$1
if [ -z $VERSION ]; then
    echo "Usage: update-version.sh NEW-VERSION"
    exit 1
fi

grep -HR "skeptic\W*\"[^\"]*\"" . | sed -e 's/^\([^:]*\):.*/\1/' | uniq | xargs sed -i "s/skeptic\(\W*\)\"\([^\"]*\)\"/skeptic\1\"$VERSION\"/g"
