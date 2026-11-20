import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import rateLimit from 'express-rate-limit';
import { env } from './config/env';
import { apiRouter } from './routes/api';

const app = express();

app.use(helmet());
app.use(
  cors({
    origin: env.allowedOrigins.length > 0 ? env.allowedOrigins : false,
    credentials: true,
  })
);
app.use(express.json({ limit: '1mb' }));

app.use(
  '/v1',
  rateLimit({
    windowMs: 60 * 1000,
    max: 120,
    standardHeaders: true,
    legacyHeaders: false,
  }),
  apiRouter
);

app.get('/health', (_req, res) => {
  res.json({ status: 'ok', service: 'techlive-api' });
});

app.listen(env.port, () => {
  console.log(`Tech Live API listening on port ${env.port}`);
});
