# aws-sig4

[AWS signature v4 request signing](http://docs.aws.amazon.com/general/latest/gr/signature-version-4.html)
implemented as an clj-http middleware. This is a pure clojure
implementation and does not require the AWS SDK. It only provides the
middleware and doesn't do anything else like autodetecting AWS
credentials. Providing correct credentials, region and service name is
the responsibility of caller.

**This software hasn't been battle tested yet. Expect bugs and problems.**

[![Circle CI](https://circleci.com/gh/sharetribe/aws-sig4/tree/master.svg?style=svg&circle-token=6d2771f17145d2db88ce255afedc97965b9dca9a)](https://circleci.com/gh/sharetribe/aws-sig4/tree/master)

## Installation

TBD after alpha pushed to clojars.

## Usage

Require clj-http client and the middleware.

```clojure
(require '[asw-sig4.middleware :as aws-sig4]
         '[clj-http.client :as http])
```

Build an auth middleware instance by passing in AWS parameters.

```clojure
(def wrap-aws-auth (aws-sig4/build-wrap-aws-auth {:region "us-east-1"
                                                  :service "es"
                                                  :access-key "AWS_ACCESS_KEY"
                                                  :secret-key "AWS_SECRET_KEY"}))

```

Wrap outgoing requests with the middleware instance. Optionally
include wrap-aws-date to ensure that that requests have either the
required Date header or if not generate X-Amz-Date
header. wrap-aws-date must be defined first in the middleware chain.

```clojure
(http/with-additional-middleware
  [wrap-aws-auth aws-sig4/wrap-aws-date]
  (http/get "https://my-es-instance.us-east-1.es.amazonaws.com/_cat/indices"))
```

That's it.

Hint: Consider using
[temporary security credentials](http://docs.aws.amazon.com/IAM/latest/UserGuide/id_credentials_temp_use-resources.html#RequestWithSTS)
when accessing AWS services.

## License

Copyright © 2015 Sharetribe Ltd.

Distributed under [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0)
