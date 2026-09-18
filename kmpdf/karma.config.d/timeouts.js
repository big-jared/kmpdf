// PDF generation renders full pages in the browser, which takes longer than Mocha's 2 second default
config.set({
    client: {
        mocha: {
            timeout: 300000
        }
    },
    browserNoActivityTimeout: 300000
});
